package com.flowforge.android.app

import com.flowforge.android.data.database.ExecutionResultEntity
import com.flowforge.android.data.database.ExecutionSessionEntity
import com.flowforge.android.data.repositories.DatasetRepository
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.repositories.WorkflowRepository
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.execution.BatchConfig
import com.flowforge.android.execution.BatchRunner
import com.flowforge.android.execution.DefaultNodeHandler
import com.flowforge.android.execution.ExecutionContext
import com.flowforge.android.execution.ItemResult
import com.flowforge.android.execution.NodeRunState
import com.flowforge.android.execution.NodeStatus
import com.flowforge.android.execution.RunControl
import com.flowforge.android.execution.RunStatus
import com.flowforge.android.execution.WorkflowEngine
import com.flowforge.android.workflow.model.newId
import com.flowforge.android.workflow.validation.Severity
import com.flowforge.android.workflow.validation.WorkflowValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RunMode { SINGLE, DATASET }

data class RunRequest(
    val workflowId: String,
    val mode: RunMode,
    val datasetId: String?,
    val config: BatchConfig,
)

data class NodeRow(val id: String, val name: String, val state: NodeRunState)

data class RunState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val workflowName: String = "",
    val mode: RunMode = RunMode.SINGLE,
    val total: Int = 0,
    val processed: Int = 0,
    val success: Int = 0,
    val failed: Int = 0,
    val startedAtMs: Long = 0,
    val elapsedMs: Long = 0,
    val avgHttpMs: Long = 0,
    val activeWorkers: Int = 0,
    val nodes: List<NodeRow> = emptyList(),
    val logs: List<String> = emptyList(),
    val finalStatus: String? = null,
    val error: String? = null,
) {
    /** Items per minute. */
    val cpm: Int get() = if (elapsedMs <= 0) 0 else (processed * 60_000L / elapsedMs).toInt()
}

/**
 * Owns the current run. It lives in the app container, so a run survives screen changes and,
 * together with RunService, app backgrounding. It does NOT survive the process being killed.
 */
class RunManager(
    private val workflows: WorkflowRepository,
    private val executions: ExecutionRepository,
    private val datasets: DatasetRepository,
    private val http: HttpClientPort,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var job: Job? = null
    private var control: RunControl? = null

    /** Returns false when a run is already active. */
    @Synchronized
    fun start(req: RunRequest): Boolean {
        if (_state.value.active) return false
        _state.value = RunState(active = true, mode = req.mode, startedAtMs = System.currentTimeMillis())
        job = scope.launch(Dispatchers.Default) { runInternal(req) }
        return true
    }

    fun pause() {
        control?.pause()
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        control?.resume()
        _state.update { it.copy(paused = false) }
    }

    fun stop() {
        job?.cancel()
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(logs = (it.logs + line).takeLast(100)) }
    }

    private fun invalid(message: String) {
        _state.update { it.copy(active = false, finalStatus = "INVALID", error = message) }
    }

    private suspend fun runInternal(req: RunRequest) {
        val wf = workflows.load(req.workflowId) ?: return invalid("Workflow not found")
        val firstError = WorkflowValidator.validate(wf).firstOrNull { it.severity == Severity.ERROR }
        if (firstError != null) return invalid(firstError.message)
        val dataset = if (req.mode == RunMode.DATASET) {
            datasets.get(req.datasetId.orEmpty()) ?: return invalid("Dataset not found")
        } else null

        val cfg = req.config.sanitized()
        val sessionId = newId()
        val startedAt = System.currentTimeMillis()
        val total = if (dataset != null) {
            val rows = (dataset.lineCount - if (cfg.skipHeader) 1 else 0).coerceAtLeast(0)
            minOf(rows, cfg.maxItems.toLong()).toInt()
        } else 1
        _state.update {
            it.copy(
                workflowName = wf.name, total = total,
                nodes = if (dataset == null) wf.nodes.map { n -> NodeRow(n.id, n.name, NodeRunState()) } else emptyList(),
            )
        }
        executions.insertSession(ExecutionSessionEntity(sessionId, wf.id, startedAt, null, "RUNNING"))

        val queue = ConcurrentLinkedQueue<ExecutionResultEntity>()
        val storedOk = AtomicInteger(0)
        val storedFail = AtomicInteger(0)
        val httpMsSum = AtomicLong(0)
        val httpCount = AtomicLong(0)
        val engine = WorkflowEngine(DefaultNodeHandler(http, this::appendLog))
        val runControl = RunControl()
        control = runControl

        suspend fun flush() {
            val batch = ArrayList<ExecutionResultEntity>()
            while (true) batch.add(queue.poll() ?: break)
            executions.insertResults(batch)
        }

        var status = "COMPLETED"
        var error: String? = null
        try {
            coroutineScope {
                val ticker = launch {
                    while (true) {
                        delay(500)
                        _state.update { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
                        flush()
                    }
                }
                try {
                    if (dataset == null) {
                        val result = engine.execute(wf, ExecutionContext(wf.variables)) { e ->
                            _state.update { s ->
                                s.copy(nodes = s.nodes.map { r -> if (r.id == e.nodeId) r.copy(state = e.state) else r })
                            }
                        }
                        val ok = result.status == RunStatus.COMPLETED || result.status == RunStatus.STOPPED
                        _state.update { it.copy(processed = 1, success = if (ok) 1 else 0, failed = if (ok) 0 else 1, error = result.error) }
                        val now = System.currentTimeMillis()
                        for ((nodeId, st) in result.nodeStates) {
                            queue.add(
                                ExecutionResultEntity(
                                    sessionId = sessionId, workflowId = wf.id, nodeId = nodeId, timestamp = now,
                                    httpStatus = st.httpStatus, durationMs = st.durationMs,
                                    status = when (st.status) {
                                        NodeStatus.PENDING -> "SKIPPED"
                                        NodeStatus.RUNNING -> "CANCELLED"
                                        else -> st.status.name
                                    },
                                    errorDetails = st.error, preview = st.preview,
                                ),
                            )
                        }
                        status = result.status.name
                    } else {
                        val source = datasets.openSource(dataset, cfg.skipHeader)
                        try {
                            BatchRunner(engine).run(
                                wf, source, cfg, runControl,
                                onActiveWorkers = { n -> _state.update { it.copy(activeWorkers = n) } },
                            ) { r: ItemResult ->
                                val ok = r.status == RunStatus.COMPLETED || r.status == RunStatus.STOPPED
                                if (r.httpStatus != null) {
                                    httpMsSum.addAndGet(r.httpMs)
                                    httpCount.incrementAndGet()
                                }
                                val line = "#${r.index} ${r.status ?: "ERROR"}" +
                                    (r.httpStatus?.let { " HTTP $it" } ?: "") + (r.error?.let { " - $it" } ?: "")
                                _state.update { s ->
                                    s.copy(
                                        processed = s.processed + 1,
                                        success = s.success + if (ok) 1 else 0,
                                        failed = s.failed + if (ok) 0 else 1,
                                        avgHttpMs = if (httpCount.get() > 0) httpMsSum.get() / httpCount.get() else 0,
                                        logs = (s.logs + line).takeLast(100),
                                    )
                                }
                                val keep = if (ok) storedOk.incrementAndGet() <= 5000 else storedFail.incrementAndGet() <= 20000
                                if (keep) {
                                    queue.add(
                                        ExecutionResultEntity(
                                            sessionId = sessionId, workflowId = wf.id, nodeId = "item-${r.index}",
                                            timestamp = System.currentTimeMillis(), httpStatus = r.httpStatus,
                                            durationMs = r.httpMs, status = if (ok) "SUCCESS" else "FAILED",
                                            errorDetails = r.error, preview = null,
                                        ),
                                    )
                                }
                            }
                        } finally {
                            source.close()
                        }
                    }
                } finally {
                    ticker.cancel()
                }
            }
        } catch (e: CancellationException) {
            status = "CANCELLED"
            throw e
        } catch (e: Exception) {
            status = "FAILED"
            error = e.message ?: e::class.java.simpleName
        } finally {
            withContext(NonCancellable) {
                try {
                    flush()
                    executions.updateSession(ExecutionSessionEntity(sessionId, wf.id, startedAt, System.currentTimeMillis(), status))
                } catch (e: Exception) {
                    error = error ?: "Could not store results: ${e.message}"
                }
                _state.update {
                    it.copy(
                        active = false, paused = false, activeWorkers = 0, finalStatus = status,
                        error = error ?: it.error, elapsedMs = System.currentTimeMillis() - startedAt,
                    )
                }
            }
        }
    }
}
