package com.flowforge.android.features.runner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.data.database.ExecutionResultEntity
import com.flowforge.android.data.database.ExecutionSessionEntity
import com.flowforge.android.data.database.WorkflowEntity
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.repositories.WorkflowRepository
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.execution.DefaultNodeHandler
import com.flowforge.android.execution.ExecutionContext
import com.flowforge.android.execution.NodeRunState
import com.flowforge.android.execution.NodeStatus
import com.flowforge.android.execution.RunResult
import com.flowforge.android.execution.WorkflowEngine
import com.flowforge.android.workflow.model.WorkflowDef
import com.flowforge.android.workflow.model.newId
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NodeRow(val id: String, val name: String, val state: NodeRunState)

data class RunnerUiState(
    val selectedId: String? = null,
    val running: Boolean = false,
    val nodes: List<NodeRow> = emptyList(),
    val logs: List<String> = emptyList(),
    val steps: Int = 0,
    val finalStatus: String? = null,
    val error: String? = null,
)

class RunnerViewModel(
    private val workflows: WorkflowRepository,
    private val executions: ExecutionRepository,
    private val http: HttpClientPort,
) : ViewModel() {
    private val _ui = MutableStateFlow(RunnerUiState())
    val ui: StateFlow<RunnerUiState> = _ui.asStateFlow()
    val workflowList: StateFlow<List<WorkflowEntity>?> =
        workflows.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private var job: Job? = null

    fun select(id: String) {
        if (!_ui.value.running) _ui.update { it.copy(selectedId = id) }
    }

    fun start() {
        val id = _ui.value.selectedId ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val wf = workflows.load(id) ?: return@launch
            val sessionId = newId()
            val startedAt = System.currentTimeMillis()
            _ui.update {
                it.copy(
                    running = true,
                    nodes = wf.nodes.map { n -> NodeRow(n.id, n.name, NodeRunState()) },
                    logs = emptyList(), steps = 0, finalStatus = null, error = null,
                )
            }
            var result: RunResult? = null
            try {
                val handler = DefaultNodeHandler(http) { line ->
                    _ui.update { s -> s.copy(logs = (s.logs + line).takeLast(200)) }
                }
                result = WorkflowEngine(handler).execute(wf, ExecutionContext(wf.variables)) { e ->
                    _ui.update { s ->
                        s.copy(nodes = s.nodes.map { r -> if (r.id == e.nodeId) r.copy(state = e.state) else r }, steps = e.step)
                    }
                }
            } finally {
                withContext(NonCancellable) { finish(wf, sessionId, startedAt, result) }
            }
        }
    }

    private suspend fun finish(wf: WorkflowDef, sessionId: String, startedAt: Long, result: RunResult?) {
        val now = System.currentTimeMillis()
        val status = result?.status?.name ?: "CANCELLED"
        val states: Map<String, NodeRunState> = result?.nodeStates ?: _ui.value.nodes.associate { it.id to it.state }
        val rows = states.map { (nodeId, st) ->
            ExecutionResultEntity(
                sessionId = sessionId, workflowId = wf.id, nodeId = nodeId, timestamp = now,
                httpStatus = st.httpStatus, durationMs = st.durationMs,
                status = when (st.status) {
                    NodeStatus.PENDING -> "SKIPPED"
                    NodeStatus.RUNNING -> "CANCELLED"
                    else -> st.status.name
                },
                errorDetails = st.error, preview = st.preview,
            )
        }
        try {
            executions.saveRun(ExecutionSessionEntity(sessionId, wf.id, startedAt, now, status), rows)
        } catch (e: Exception) {
            _ui.update { it.copy(error = "Could not store results: ${e.message}") }
        }
        _ui.update { it.copy(running = false, finalStatus = status, error = it.error ?: result?.error) }
    }

    fun stop() {
        job?.cancel()
    }
}

@Composable
fun RunnerScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: RunnerViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RunnerViewModel(container.workflowRepository, container.executionRepository, container.httpEngine) }
        },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val list by vm.workflowList.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.rn_workflow), style = MaterialTheme.typography.titleMedium)
        if (list.isNullOrEmpty()) Text(stringResource(R.string.rn_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        list?.forEach { wf ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = ui.selectedId == wf.id, enabled = !ui.running, role = Role.RadioButton,
                    onClick = { vm.select(wf.id) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.selectedId == wf.id, onClick = null, enabled = !ui.running)
                Text(wf.name, Modifier.padding(start = 12.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::start, enabled = ui.selectedId != null && !ui.running) { Text(stringResource(R.string.rn_start)) }
            OutlinedButton(onClick = vm::stop, enabled = ui.running) { Text(stringResource(R.string.rn_stop)) }
        }
        if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.rn_steps, ui.steps), style = MaterialTheme.typography.bodySmall)
        ui.finalStatus?.let { Text(stringResource(R.string.rn_result, it), style = MaterialTheme.typography.titleSmall) }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (ui.nodes.isNotEmpty()) {
            Text(stringResource(R.string.rn_nodes), style = MaterialTheme.typography.titleMedium)
            ui.nodes.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(row.state.status.name, style = MaterialTheme.typography.labelMedium)
                        }
                        val meta = listOfNotNull(
                            row.state.httpStatus?.let { "HTTP $it" },
                            if (row.state.durationMs > 0) "${row.state.durationMs} ms" else null,
                            row.state.error,
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (ui.logs.isNotEmpty()) {
            Text(stringResource(R.string.rn_logs), style = MaterialTheme.typography.titleMedium)
            ui.logs.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
