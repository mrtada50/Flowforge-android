package com.flowforge.android.execution

import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.WorkflowDef
import com.flowforge.android.workflow.validation.Severity
import com.flowforge.android.workflow.validation.WorkflowValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Walks the workflow graph one node at a time (a single token). Independent of the UI and of Android.
 * Parallelism happens above this class: many runs can execute concurrently (see the Runner).
 */
class WorkflowEngine(private val handler: NodeHandler) {

    suspend fun execute(
        wf: WorkflowDef,
        ctx: ExecutionContext = ExecutionContext(wf.variables),
        onEvent: (ExecutionEvent) -> Unit = {},
    ): RunResult {
        val startNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNs) / 1_000_000

        val issues = WorkflowValidator.validate(wf)
        val firstError = issues.firstOrNull { it.severity == Severity.ERROR }
        if (firstError != null) {
            return RunResult(RunStatus.INVALID, emptyMap(), 0, firstError.message, 0, issues)
        }

        ctx.settings = wf.settings
        val nodes = wf.nodes.associateBy { it.id }
        val outgoing = wf.connections.associateBy { it.fromNode to it.fromPort }
        val states = LinkedHashMap<String, NodeRunState>()
        wf.nodes.forEach { states[it.id] = NodeRunState() }
        var steps = 0
        var runningId: String? = null

        fun finish(status: RunStatus, error: String? = null) =
            RunResult(status, LinkedHashMap(states), steps, error, elapsedMs(), issues)

        var current = wf.nodes.first { it.type == NodeType.START }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (steps >= wf.settings.maxSteps) {
                    return finish(RunStatus.LIMIT_EXCEEDED, "Step limit reached (${wf.settings.maxSteps})")
                }
                val remaining = wf.settings.totalTimeoutMs - elapsedMs()
                if (remaining <= 0) return finish(RunStatus.TIMED_OUT, "Total run time exceeded")
                steps++

                val node = current
                runningId = node.id
                states[node.id] = NodeRunState(NodeStatus.RUNNING)
                onEvent(ExecutionEvent(node.id, node.name, states.getValue(node.id), steps))

                val t0 = System.nanoTime()
                val errorPort = if ("error" in NodeSpecs.of(node.type).outputs) "error" else null
                val outcome: NodeOutcome = try {
                    val limit = minOf(wf.settings.nodeTimeoutMs, remaining)
                    withTimeoutOrNull(limit) { handler.execute(node, ctx) }
                        ?: NodeOutcome(NodeStatus.FAILED, errorPort, error = "Node timed out after ${limit}ms")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NodeOutcome(NodeStatus.FAILED, errorPort, error = e.message ?: e::class.java.simpleName)
                }

                val state = NodeRunState(
                    outcome.status, outcome.outPort, outcome.httpStatus,
                    (System.nanoTime() - t0) / 1_000_000, outcome.error, outcome.preview,
                )
                states[node.id] = state
                runningId = null
                onEvent(ExecutionEvent(node.id, node.name, state, steps))
                ctx.lastFailed = outcome.status == NodeStatus.FAILED

                if (node.type == NodeType.END) return finish(RunStatus.COMPLETED)
                if (node.type == NodeType.STOP) return finish(RunStatus.STOPPED)

                val port = outcome.outPort
                val conn = port?.let { outgoing[node.id to it] }
                if (conn == null) {
                    return if (outcome.status == NodeStatus.FAILED) {
                        finish(RunStatus.FAILED, "${node.name}: ${outcome.error ?: "failed"}")
                    } else {
                        finish(RunStatus.FAILED, "${node.name}: dead end (output '$port' is not connected)")
                    }
                }
                current = nodes.getValue(conn.toNode)
            }
        } catch (e: CancellationException) {
            runningId?.let { id ->
                val n = nodes.getValue(id)
                states[id] = NodeRunState(NodeStatus.CANCELLED)
                onEvent(ExecutionEvent(id, n.name, states.getValue(id), steps))
            }
            throw e
        }
    }
}
