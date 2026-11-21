package com.flowforge.android.execution

import com.flowforge.android.domain.interfaces.DatasetProvider
import com.flowforge.android.domain.models.HttpResult
import com.flowforge.android.workflow.model.ExecutionSettings
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.validation.ValidationIssue

enum class NodeStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, CANCELLED }

enum class RunStatus { COMPLETED, FAILED, CANCELLED, STOPPED, INVALID, LIMIT_EXCEEDED, TIMED_OUT }

data class NodeOutcome(
    val status: NodeStatus,
    /** Output port to follow, or null to end the run at this node. */
    val outPort: String?,
    val httpStatus: Int? = null,
    val error: String? = null,
    /** Redacted, size-limited preview (e.g. response body). */
    val preview: String? = null,
)

data class NodeRunState(
    val status: NodeStatus = NodeStatus.PENDING,
    val outPort: String? = null,
    val httpStatus: Int? = null,
    val durationMs: Long = 0,
    val error: String? = null,
    val preview: String? = null,
)

data class ExecutionEvent(val nodeId: String, val nodeName: String, val state: NodeRunState, val step: Int)

data class RunResult(
    val status: RunStatus,
    val nodeStates: Map<String, NodeRunState>,
    val steps: Int,
    val error: String?,
    val durationMs: Long,
    val issues: List<ValidationIssue> = emptyList(),
)

class ExecutionContext(
    initialVariables: Map<String, String> = emptyMap(),
    val datasets: DatasetProvider? = null,
) {
    val variables: MutableMap<String, String> = LinkedHashMap(initialVariables)
    var lastResponse: HttpResult? = null
    var lastFailed: Boolean = false
    val loopCounters: MutableMap<String, Int> = HashMap()
    var settings: ExecutionSettings = ExecutionSettings()

    fun resolve(template: String): String = VariableResolver.resolve(template, variables)
}

interface NodeHandler {
    suspend fun execute(node: NodeDef, ctx: ExecutionContext): NodeOutcome
}
