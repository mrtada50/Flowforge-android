package com.flowforge.android.features.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowforge.android.data.repositories.WorkflowRepository
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
)

class EditorViewModel(private val repo: WorkflowRepository, private val workflowId: String) : ViewModel() {
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

    fun deleteSelected() {
        val s = _ui.value
        val wf = s.workflow ?: return
        val node = wf.nodes.firstOrNull { it.id == s.selectedNodeId } ?: return post(EditorMessage.SelectFirst)
        if (node.type == NodeType.START) return post(EditorMessage.StartProtected)
        commit(
            wf.copy(
                nodes = wf.nodes.filterNot { it.id == node.id },
                connections = wf.connections.filterNot { it.fromNode == node.id || it.toNode == node.id },
            ),
            selected = null,
        )
        _ui.update { it.copy(pendingFrom = null) }
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
