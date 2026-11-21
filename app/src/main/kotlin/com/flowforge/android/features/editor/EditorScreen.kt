package com.flowforge.android.features.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.validation.Severity
import com.flowforge.android.workflow.validation.ValidationIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(workflowId: String) {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowForgeApp).container
    val vm: EditorViewModel = viewModel(
        key = "editor-$workflowId",
        factory = viewModelFactory { initializer { EditorViewModel(container.workflowRepository, workflowId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.flush() } }

    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var issues by remember { mutableStateOf<List<ValidationIssue>?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = vm.exportJson()
        if (uri != null && text != null) {
            scope.launch {
                val okMsg = context.getString(R.string.ed_export_done)
                val badMsg = context.getString(R.string.ed_export_failed)
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream")
                    }.isSuccess
                }
                vm.postMessage(if (ok) okMsg else badMsg)
            }
        }
    }

    val wf = ui.workflow
    when {
        !ui.loaded -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        wf == null -> Box(Modifier.fillMaxSize()) { Text(stringResource(R.string.ed_not_found), Modifier.align(Alignment.Center)) }
        else -> {
            val selected = wf.nodes.firstOrNull { it.id == ui.selectedNodeId }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    TextButton(onClick = vm::undo, enabled = ui.canUndo) { Text(stringResource(R.string.ed_undo)) }
                    TextButton(onClick = vm::redo, enabled = ui.canRedo) { Text(stringResource(R.string.ed_redo)) }
                    TextButton(onClick = { vm.transform(viewport.width / 2f, viewport.height / 2f, 0f, 0f, 1.25f) }) {
                        Text(stringResource(R.string.ed_zoom_in))
                    }
                    TextButton(onClick = { vm.transform(viewport.width / 2f, viewport.height / 2f, 0f, 0f, 0.8f) }) {
                        Text(stringResource(R.string.ed_zoom_out))
                    }
                    Box {
                        TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.ed_add_node)) }
                        DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                            NodeType.entries.filter { it != NodeType.START }.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.title) },
                                    onClick = {
                                        showAdd = false
                                        vm.addNode(type, -ui.panX / density / ui.scale + 40f, -ui.panY / density / ui.scale + 40f)
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        if (selected != null) showEdit = true else vm.postMessage(context.getString(R.string.ed_select_first))
                    }) {
                        Text(stringResource(R.string.ed_edit))
                    }
                    TextButton(onClick = vm::duplicateSelected) { Text(stringResource(R.string.ed_duplicate)) }
                    TextButton(onClick = vm::deleteSelected) { Text(stringResource(R.string.ed_delete)) }
                    TextButton(onClick = { issues = vm.validate() }) { Text(stringResource(R.string.ed_validate)) }
                    TextButton(onClick = {
                        exportLauncher.launch(wf.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_") + ".json")
                    }) { Text(stringResource(R.string.ed_export)) }
                }
                Text(
                    stringResource(if (ui.saving) R.string.ed_saving else R.string.ed_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The canvas is always laid out left-to-right so that port positions stay predictable.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Canvas2D(wf.nodes, wf, ui, vm, density) { viewport = it }
                    }
                    ui.pendingFrom?.let {
                        Banner(stringResource(R.string.ed_connect_hint), Modifier.align(Alignment.TopCenter), null)
                    }
                    ui.message?.let { m ->
                        val text = when (m) {
                            EditorMessage.PickOutput -> stringResource(R.string.ed_pick_output)
                            EditorMessage.StartProtected -> stringResource(R.string.ed_start_protected)
                            EditorMessage.SelectFirst -> stringResource(R.string.ed_select_first)
                            is EditorMessage.Text -> m.text
                        }
                        Banner(text, Modifier.align(Alignment.BottomCenter)) { vm.clearMessage() }
                    }
                }
            }

            if (showEdit && selected != null) {
                NodeEditDialog(
                    node = selected,
                    onDismiss = { showEdit = false },
                    onSave = { name, cfg -> vm.updateNode(selected.id, name, cfg); showEdit = false },
                )
            }
            issues?.let { list -> IssuesDialog(list) { issues = null } }
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier, onClick: (() -> Unit)?) {
    Surface(
        modifier = modifier.padding(12.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Canvas2D(
    nodes: List<NodeDef>,
    wf: com.flowforge.android.workflow.model.WorkflowDef,
    ui: EditorUiState,
    vm: EditorViewModel,
    density: Float,
    onViewport: (IntSize) -> Unit,
) {
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged(onViewport)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ -> vm.transform(centroid.x, centroid.y, pan.x, pan.y, zoom) }
            },
    ) {
        Box(
            Modifier
                .requiredSize(WORLD_SIZE.dp)
                .graphicsLayer(
                    scaleX = ui.scale, scaleY = ui.scale,
                    translationX = ui.panX, translationY = ui.panY,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
        ) {
            Canvas(Modifier.matchParentSize()) {
                wf.connections.forEach { c ->
                    val from = byId[c.fromNode] ?: return@forEach
                    val to = byId[c.toNode] ?: return@forEach
                    val oi = NodeSpecs.of(from.type).outputs.indexOf(c.fromPort)
                    val ii = NodeSpecs.of(to.type).inputs.indexOf(c.toPort)
                    if (oi < 0 || ii < 0) return@forEach
                    val a = portCenter(from, true, oi)
                    val b = portCenter(to, false, ii)
                    val ax = a.x.dp.toPx(); val ay = a.y.dp.toPx()
                    val bx = b.x.dp.toPx(); val bY = b.y.dp.toPx()
                    val dx = maxOf(60.dp.toPx(), kotlin.math.abs(bx - ax) / 2f)
                    val path = Path().apply {
                        moveTo(ax, ay)
                        cubicTo(ax + dx, ay, bx - dx, bY, bx, bY)
                    }
                    drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
                }
            }
            nodes.forEach { node ->
                androidx.compose.runtime.key(node.id) {
                    NodeView(
                        node = node,
                        selected = node.id == ui.selectedNodeId,
                        pending = ui.pendingFrom,
                        density = density,
                        onTap = { vm.select(node.id) },
                        onDragStart = { vm.select(node.id); vm.beginMove() },
                        onDrag = { dx, dy -> vm.moveNode(node.id, dx, dy) },
                        onDragEnd = { vm.endMove() },
                        onOutputTap = { vm.onOutputTap(node.id, it) },
                        onInputTap = { vm.onInputTap(node.id, it) },
                        onPortLong = { port, output -> vm.disconnect(node.id, port, output) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeView(
    node: NodeDef,
    selected: Boolean,
    pending: Pair<String, String>?,
    density: Float,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onOutputTap: (String) -> Unit,
    onInputTap: (String) -> Unit,
    onPortLong: (String, Boolean) -> Unit,
) {
    val spec = NodeSpecs.of(node.type)
    val rows = maxOf(spec.inputs.size, spec.outputs.size, 1)
    val scheme = MaterialTheme.colorScheme
    val headerColor = when (node.type) {
        NodeType.START, NodeType.END, NodeType.STOP -> scheme.secondary
        NodeType.HTTP_REQUEST -> scheme.primary
        else -> scheme.onSurfaceVariant
    }.copy(alpha = 0.28f)
    val shape = RoundedCornerShape(10.dp)

    Box(
        Modifier
            .absoluteOffset(node.x.dp, node.y.dp)
            .size(NODE_W.dp, nodeHeight(node.type).dp)
            .clip(shape)
            .background(scheme.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outline, shape)
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, drag ->
                    change.consume()
                    onDrag(drag.x / density, drag.y / density)
                }
            }
            .clickable(onClick = onTap),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().height(HEADER_H.dp).background(headerColor).padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
            repeat(rows) { i ->
                val inPort = spec.inputs.getOrNull(i)
                val outPort = spec.outputs.getOrNull(i)
                Row(
                    Modifier.fillMaxWidth().height(ROW_H.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inPort != null) PortDot(false, { onInputTap(inPort) }, { onPortLong(inPort, false) })
                    else Spacer(Modifier.size(24.dp))
                    Text(inPort.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(
                        outPort.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.End, maxLines = 1,
                    )
                    if (outPort != null) PortDot(pending == (node.id to outPort), { onOutputTap(outPort) }, { onPortLong(outPort, true) })
                    else Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortDot(active: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.size(24.dp).combinedClickable(onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

@Composable
private fun NodeEditDialog(node: NodeDef, onDismiss: () -> Unit, onSave: (String, Map<String, String>) -> Unit) {
    val spec = NodeSpecs.of(node.type)
    var name by remember(node.id) { mutableStateOf(node.name) }
    val values = remember(node.id) {
        mutableStateMapOf<String, String>().apply { spec.fields.forEach { put(it.key, node.config[it.key] ?: it.default) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(node.type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ed_node_name)) }, modifier = Modifier.fillMaxWidth(),
                )
                spec.fields.forEach { f ->
                    val hint: (@Composable () -> Unit)? = if (f.hint.isNotEmpty()) ({ Text(f.hint) }) else null
                    OutlinedTextField(
                        value = values[f.key].orEmpty(),
                        onValueChange = { values[f.key] = it },
                        label = { Text(f.label + if (f.required) " *" else "") },
                        supportingText = hint,
                        singleLine = !f.multiline,
                        minLines = if (f.multiline) 3 else 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), values.toMap()) }) {
                Text(stringResource(R.string.ed_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ed_close)) } },
    )
}

@Composable
private fun IssuesDialog(issues: List<ValidationIssue>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_issues_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (issues.isEmpty()) Text(stringResource(R.string.ed_valid))
                issues.forEach {
                    Text(
                        (if (it.severity == Severity.ERROR) "✖ " else "⚠ ") + it.message,
                        color = if (it.severity == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ed_close)) } },
    )
}
