package com.flowforge.android.features.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowforge.android.app.AppContainer
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.execution.DefaultNodeHandler
import com.flowforge.android.execution.ExecutionContext
import com.flowforge.android.execution.WorkflowEngine
import com.flowforge.android.workflow.model.ConnectionDef
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.WorkflowDef
import com.flowforge.android.workflow.model.newId
import com.flowforge.android.workflow.model.newNode
import com.flowforge.android.workflow.serialization.WorkflowJson
import com.flowforge.android.workflow.validation.ValidationIssue
import com.flowforge.android.workflow.validation.WorkflowValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NODE_W = 170f
const val HEADER_H = 32f
const val ROW_H = 28f
const val WORLD_SIZE = 3000f

fun nodeHeight(type: NodeType): Float {
    val spec = NodeSpecs.of(type)
    return HEADER_H + ROW_H * maxOf(spec.inputs.size, spec.outputs.size, 1) + 4f
}

/** Port centre in world coordinates (dp). */
fun portCenter(node: NodeDef, output: Boolean, index: Int) = Offset(
    node.x + if (output) NODE_W - 16f else 16f,
    node.y + HEADER_H + ROW_H * index + ROW_H / 2f,
)

sealed interface EditorMessage {
    data object PickOutput : EditorMessage
    data object StartProtected : EditorMessage
    data object SelectFirst : EditorMessage
    data class Text(val text: String) : EditorMessage
}

data class EditorUiState(
    val loaded: Boolean = false,
    val workflow: WorkflowDef? = null,
    val selectedNodeId: String? = null,
    /** Output port waiting for an input port (nodeId to port). */
    val pendingFrom: Pair<String, String>? = null,
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val message: EditorMessage? = null,
    val viewMode: EditorViewMode = EditorViewMode.STACK,
    val debugRunning: Boolean = false,
    val debugLines: List<String> = emptyList(),
)

enum class EditorViewMode { STACK, CANVAS }

class EditorViewModel(private val container: AppContainer, private val workflowId: String) : ViewModel() {
    private val repo = container.workflowRepository
    private val http: HttpClientPort get() = container.httpEngine
    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    private val undoStack = ArrayDeque<WorkflowDef>()
    private val redoStack = ArrayDeque<WorkflowDef>()
    private var saveJob: Job? = null
    private var dirty = false

    init {
        viewModelScope.launch {
            val wf = repo.load(workflowId)
            _ui.update { it.copy(loaded = true, workflow = wf) }
        }
    }

    // ---- history & persistence ----
    private fun commit(next: WorkflowDef, selected: String? = _ui.value.selectedNodeId) {
        _ui.value.workflow?.let {
            undoStack.addLast(it)
            if (undoStack.size > 100) undoStack.removeFirst()
        }
        redoStack.clear()
        _ui.update { it.copy(workflow = next, selectedNodeId = selected, canUndo = undoStack.isNotEmpty(), canRedo = false) }
        markDirty()
    }

    private fun apply(wf: WorkflowDef) {
        _ui.update { s ->
            s.copy(
                workflow = wf,
                selectedNodeId = s.selectedNodeId?.takeIf { id -> wf.nodes.any { it.id == id } },
                pendingFrom = null,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
        markDirty()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val cur = _ui.value.workflow ?: return
        redoStack.addLast(cur)
        apply(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val cur = _ui.value.workflow ?: return
        undoStack.addLast(cur)
        apply(next)
    }

    private fun markDirty() {
        dirty = true
        saveJob?.cancel()
        _ui.update { it.copy(saving = true) }
        saveJob = viewModelScope.launch {
            delay(500)
            persist()
        }
    }

    private suspend fun persist() {
        val wf = _ui.value.workflow ?: return
        repo.save(wf)
        dirty = false
        _ui.update { it.copy(saving = false) }
    }

    /** Called when the screen is left: make sure nothing is lost. */
    fun flush() {
        if (!dirty) return
        saveJob?.cancel()
        viewModelScope.launch { withContext(NonCancellable) { persist() } }
    }

    // ---- viewport ----
    fun transform(cx: Float, cy: Float, panDx: Float, panDy: Float, zoom: Float) {
        _ui.update { s ->
            val ns = (s.scale * zoom).coerceIn(0.3f, 2.5f)
            val k = ns / s.scale
            s.copy(scale = ns, panX = cx - (cx - s.panX) * k + panDx, panY = cy - (cy - s.panY) * k + panDy)
        }
    }

    // ---- selection ----
    fun select(id: String?) = _ui.update { it.copy(selectedNodeId = id) }
    fun setViewMode(mode: EditorViewMode) = _ui.update { it.copy(viewMode = mode) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun postMessage(text: String) = _ui.update { it.copy(message = EditorMessage.Text(text)) }
    private fun post(m: EditorMessage) = _ui.update { it.copy(message = m) }

    // ---- dragging (one undo step per gesture) ----
    fun beginMove() {
        val cur = _ui.value.workflow ?: return
        undoStack.addLast(cur)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        _ui.update { it.copy(canUndo = true, canRedo = false) }
    }

    fun moveNode(id: String, dx: Float, dy: Float) {
        _ui.update { s ->
            val wf = s.workflow ?: return@update s
            s.copy(
                workflow = wf.copy(
                    nodes = wf.nodes.map {
                        if (it.id == id) {
                            it.copy(
                                x = (it.x + dx).coerceIn(0f, WORLD_SIZE - NODE_W),
                                y = (it.y + dy).coerceIn(0f, WORLD_SIZE - 200f),
                            )
                        } else it
                    },
                ),
            )
        }
    }

    fun endMove() = markDirty()

    // ---- structure ----
    fun addNode(type: NodeType, x: Float, y: Float) {
        val wf = _ui.value.workflow ?: return
        val node = newNode(type, x.coerceIn(0f, WORLD_SIZE - NODE_W), y.coerceIn(0f, WORLD_SIZE - 200f))
        commit(wf.copy(nodes = wf.nodes + node), selected = node.id)
    }

    fun updateNode(id: String, name: String, config: Map<String, String>) {
        val wf = _ui.value.workflow ?: return
        commit(wf.copy(nodes = wf.nodes.map { if (it.id == id) it.copy(name = name, config = it.config + config) else it }))
    }

    /** Deletes the node and bridges the gap: whatever pointed into it now points to what it pointed out to via its primary port. */
    fun deleteSelected() {
        val s = _ui.value
        val wf = s.workflow ?: return
        val node = wf.nodes.firstOrNull { it.id == s.selectedNodeId } ?: return post(EditorMessage.SelectFirst)
        if (node.type == NodeType.START) return post(EditorMessage.StartProtected)
        val successor = wf.connections.firstOrNull { it.fromNode == node.id && it.fromPort == primaryPort(node.type) }
        val bridged = wf.connections.filter { it.toNode == node.id }.mapNotNull { inConn ->
            successor?.let { ConnectionDef(newId(), inConn.fromNode, inConn.fromPort, it.toNode, it.toPort) }
        }
        commit(
            wf.copy(
                nodes = wf.nodes.filterNot { it.id == node.id },
                connections = wf.connections.filterNot { it.fromNode == node.id || it.toNode == node.id } + bridged,
            ),
            selected = null,
        )
        _ui.update { it.copy(pendingFrom = null) }
    }

    // ---- stack (linear) view: insert / reorder along each node's primary (first) output port ----

    private fun primaryPort(type: NodeType): String? = NodeSpecs.of(type).outputs.firstOrNull()

    /** Nodes in primary-chain order, following each node's first output port, starting at Start. */
    fun primaryChain(): List<NodeDef> {
        val wf = _ui.value.workflow ?: return emptyList()
        val byId = wf.nodes.associateBy { it.id }
        val start = wf.nodes.firstOrNull { it.type == NodeType.START } ?: return emptyList()
        val chain = mutableListOf(start)
        val seen = mutableSetOf(start.id)
        var current = start
        while (current.type != NodeType.END && current.type != NodeType.STOP) {
            val port = primaryPort(current.type) ?: break
            val next = wf.connections.firstOrNull { it.fromNode == current.id && it.fromPort == port }?.let { byId[it.toNode] } ?: break
            if (!seen.add(next.id)) break
            chain.add(next)
            current = next
        }
        return chain
    }

    /** Nodes that exist but are not part of the primary chain (reached only via a branch, or unwired). */
    fun unlinkedNodes(): List<NodeDef> {
        val wf = _ui.value.workflow ?: return emptyList()
        val inChain = primaryChain().map { it.id }.toSet()
        return wf.nodes.filterNot { it.id in inChain }
    }

    /** Rebuilds only the primary-chain edges (each listed node's first-output-port edge) to match this order.
     *  Any other connection (a branch's secondary port, a loop-back edge) is left untouched. */
    private fun rewirePrimaryChain(wf: WorkflowDef, order: List<NodeDef>): WorkflowDef {
        val orderedIds = order.map { it.id }.toSet()
        val keep = wf.connections.filterNot { c ->
            val from = wf.nodes.firstOrNull { it.id == c.fromNode }
            from != null && c.fromNode in orderedIds && c.fromPort == primaryPort(from.type)
        }
        val rebuilt = order.zipWithNext().mapNotNull { (a, b) ->
            primaryPort(a.type)?.let { ConnectionDef(newId(), a.id, it, b.id, "in") }
        }
        return wf.copy(connections = keep + rebuilt)
    }

    /** Adds a block to the main stack, right after the selected block (or right before End if none selected). */
    fun addToStack(type: NodeType) {
        val wf = _ui.value.workflow ?: return
        val chain = primaryChain()
        if (chain.size < 2) return
        val selId = _ui.value.selectedNodeId
        val afterIndex = chain.indexOfFirst { it.id == selId }.let { if (it in 0 until chain.lastIndex) it else chain.lastIndex - 1 }
        val node = newNode(type, 0f, 0f)
        val newOrder = chain.toMutableList().apply { add(afterIndex + 1, node) }
        val rewired = rewirePrimaryChain(wf.copy(nodes = wf.nodes + node), newOrder)
        commit(rewired, selected = node.id)
    }

    /** Moves a stack block up or down (swaps it with its neighbour) and rewires the chain to match. */
    fun moveInStack(nodeId: String, up: Boolean) {
        val wf = _ui.value.workflow ?: return
        val chain = primaryChain().toMutableList()
        val i = chain.indexOfFirst { it.id == nodeId }
        val j = if (up) i - 1 else i + 1
        if (i <= 0 || j <= 0 || j >= chain.size - 1) return // keep Start/End fixed at the ends
        val tmp = chain[i]; chain[i] = chain[j]; chain[j] = tmp
        commit(rewirePrimaryChain(wf, chain))
    }

    // ---- inline quick debug run (single execution, current workflow) ----

    fun runDebug() {
        val wf = _ui.value.workflow ?: return
        if (_ui.value.debugRunning) return
        _ui.update { it.copy(debugRunning = true, debugLines = emptyList()) }
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            val ctx = ExecutionContext(wf.variables)
            val result = try {
                WorkflowEngine(DefaultNodeHandler(http) { lines.add(it) }).execute(wf, ctx)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(debugRunning = false, debugLines = listOf("BOT STATUS: ERROR", e.message ?: e::class.java.simpleName))
                }
                return@launch
            }
            lines.add(0, "BOT STATUS: ${result.status}" + (result.error?.let { " ($it)" } ?: ""))
            lines.add("VARIABLES:")
            ctx.variables.forEach { (k, v) -> lines.add("  $k = ${v.take(200)}") }
            _ui.update { it.copy(debugRunning = false, debugLines = lines) }
        }
    }

    fun duplicateSelected() {
        val s = _ui.value
        val wf = s.workflow ?: return
        val node = wf.nodes.firstOrNull { it.id == s.selectedNodeId } ?: return post(EditorMessage.SelectFirst)
        if (node.type == NodeType.START) return post(EditorMessage.StartProtected)
        val copy = node.copy(id = newId(), name = node.name + " copy", x = node.x + 30f, y = node.y + 30f)
        commit(wf.copy(nodes = wf.nodes + copy), selected = copy.id)
    }

    // ---- connections ----
    fun onOutputTap(nodeId: String, port: String) =
        _ui.update { it.copy(pendingFrom = if (it.pendingFrom == nodeId to port) null else nodeId to port) }

    fun onInputTap(nodeId: String, port: String) {
        val s = _ui.value
        val wf = s.workflow ?: return
        val from = s.pendingFrom ?: return post(EditorMessage.PickOutput)
        val reason = WorkflowValidator.canConnect(wf, from.first, from.second, nodeId, port)
        if (reason != null) return post(EditorMessage.Text(reason))
        commit(wf.copy(connections = wf.connections + ConnectionDef(newId(), from.first, from.second, nodeId, port)))
        _ui.update { it.copy(pendingFrom = null) }
    }

    fun disconnect(nodeId: String, port: String, output: Boolean) {
        val wf = _ui.value.workflow ?: return
        val kept = wf.connections.filterNot {
            if (output) it.fromNode == nodeId && it.fromPort == port else it.toNode == nodeId && it.toPort == port
        }
        if (kept.size != wf.connections.size) commit(wf.copy(connections = kept))
        _ui.update { it.copy(pendingFrom = null) }
    }

    // ---- validation & export ----
    fun validate(): List<ValidationIssue> = _ui.value.workflow?.let { WorkflowValidator.validate(it) } ?: emptyList()
    fun exportJson(): String? = _ui.value.workflow?.let { WorkflowJson.encode(it) }
}
