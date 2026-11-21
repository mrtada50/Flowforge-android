package com.flowforge.android.features.runner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.AppContainer
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.app.RunMode
import com.flowforge.android.app.RunRequest
import com.flowforge.android.app.RunService
import com.flowforge.android.app.RunState
import com.flowforge.android.data.database.DatasetEntity
import com.flowforge.android.data.database.WorkflowEntity
import com.flowforge.android.execution.BatchConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class RunnerForm(
    val workflowId: String? = null,
    val mode: RunMode = RunMode.SINGLE,
    val datasetId: String? = null,
    val concurrency: Int = BatchConfig.DEFAULT_CONCURRENCY,
    val rate: Int = BatchConfig.DEFAULT_RATE,
    val maxItems: Int = 100,
    val delimiter: String = "",
    val skipHeader: Boolean = false,
)

enum class StartResult { STARTED, NO_WORKFLOW, NO_DATASET, BUSY }

class RunnerViewModel(private val c: AppContainer) : ViewModel() {
    val workflows: StateFlow<List<WorkflowEntity>?> =
        c.workflowRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val datasets: StateFlow<List<DatasetEntity>> =
        c.datasetRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runState: StateFlow<RunState> = c.runManager.state
    val activeProxy: StateFlow<String?> = c.proxyRepository.activeConfig
        .map { it?.toString() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _form = MutableStateFlow(RunnerForm())
    val form: StateFlow<RunnerForm> = _form.asStateFlow()

    fun update(f: RunnerForm.() -> RunnerForm) = _form.update { it.f() }

    fun start(): StartResult {
        val f = _form.value
        val wf = f.workflowId ?: return StartResult.NO_WORKFLOW
        if (f.mode == RunMode.DATASET && f.datasetId == null) return StartResult.NO_DATASET
        val config = BatchConfig(f.concurrency, f.rate, f.maxItems, f.delimiter, f.skipHeader)
        val ok = c.runManager.start(RunRequest(wf, f.mode, f.datasetId, config))
        return if (ok) StartResult.STARTED else StartResult.BUSY
    }

    fun pause() = c.runManager.pause()
    fun resume() = c.runManager.resume()
    fun stop() = c.runManager.stop()
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun RunnerScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowForgeApp).container
    val vm: RunnerViewModel = viewModel(factory = viewModelFactory { initializer { RunnerViewModel(container) } })
    val form by vm.form.collectAsStateWithLifecycle()
    val run by vm.runState.collectAsStateWithLifecycle()
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val datasets by vm.datasets.collectAsStateWithLifecycle()
    val proxy by vm.activeProxy.collectAsStateWithLifecycle()
    var startError by remember { mutableStateOf<StartResult?>(null) }

    fun begin() {
        val r = vm.start()
        if (r == StartResult.STARTED) {
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
            startError = null
        } else {
            startError = r
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }
    fun onStartClick() {
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else begin()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.rn_workflow), style = MaterialTheme.typography.titleMedium)
        if (workflows.isNullOrEmpty()) Text(stringResource(R.string.rn_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        workflows?.forEach { wf ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = form.workflowId == wf.id, enabled = !run.active, role = Role.RadioButton,
                    onClick = { vm.update { copy(workflowId = wf.id) } },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = form.workflowId == wf.id, onClick = null, enabled = !run.active)
                Text(wf.name, Modifier.padding(start = 12.dp))
            }
        }

        Text(stringResource(R.string.rn_mode), style = MaterialTheme.typography.titleMedium)
        ChipRow(
            listOf(RunMode.SINGLE to stringResource(R.string.rn_mode_single), RunMode.DATASET to stringResource(R.string.rn_mode_dataset)),
            form.mode,
        ) { m -> if (!run.active) vm.update { copy(mode = m) } }

        if (form.mode == RunMode.DATASET) {
            Text(stringResource(R.string.rn_dataset), style = MaterialTheme.typography.titleSmall)
            if (datasets.isEmpty()) Text(stringResource(R.string.rn_no_datasets), color = MaterialTheme.colorScheme.onSurfaceVariant)
            datasets.forEach { ds ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = form.datasetId == ds.id, enabled = !run.active, role = Role.RadioButton,
                        onClick = { vm.update { copy(datasetId = ds.id) } },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = form.datasetId == ds.id, onClick = null, enabled = !run.active)
                    Text("${ds.name} (${ds.lineCount})", Modifier.padding(start = 12.dp))
                }
            }
            Text(stringResource(R.string.rn_concurrency), style = MaterialTheme.typography.titleSmall)
            ChipRow((1..BatchConfig.MAX_CONCURRENCY).filter { it == 1 || it == 2 || it == 4 || it == 8 }.map { it to "$it" }, form.concurrency) { v ->
                if (!run.active) vm.update { copy(concurrency = v) }
            }
            Text(stringResource(R.string.rn_rate), style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(1, 2, 5, 10, BatchConfig.MAX_RATE).map { it to "$it" }, form.rate) { v ->
                if (!run.active) vm.update { copy(rate = v) }
            }
            Text(stringResource(R.string.rn_max_items), style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf(10, 100, 1000, 10000, BatchConfig.MAX_ITEMS).map { it to "$it" }, form.maxItems) { v ->
                if (!run.active) vm.update { copy(maxItems = v) }
            }
            Text(stringResource(R.string.rn_delimiter), style = MaterialTheme.typography.titleSmall)
            ChipRow(
                listOf("" to stringResource(R.string.rn_delim_none), "," to ",", ";" to ";", ":" to ":", "\t" to "TAB"),
                form.delimiter,
            ) { v -> if (!run.active) vm.update { copy(delimiter = v) } }
            ChipRow(
                listOf(false to stringResource(R.string.rn_header_no), true to stringResource(R.string.rn_header_yes)),
                form.skipHeader,
            ) { v -> if (!run.active) vm.update { copy(skipHeader = v) } }
            Text(stringResource(R.string.rn_limits_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text(
            stringResource(R.string.rn_proxy, proxy ?: stringResource(R.string.rn_proxy_none)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStartClick() }, enabled = !run.active) { Text(stringResource(R.string.rn_start)) }
            if (run.paused) {
                OutlinedButton(onClick = vm::resume, enabled = run.active) { Text(stringResource(R.string.rn_resume)) }
            } else {
                OutlinedButton(onClick = vm::pause, enabled = run.active && run.mode == RunMode.DATASET) { Text(stringResource(R.string.rn_pause)) }
            }
            OutlinedButton(onClick = vm::stop, enabled = run.active) { Text(stringResource(R.string.rn_stop)) }
        }
        startError?.let {
            Text(
                stringResource(
                    when (it) {
                        StartResult.NO_WORKFLOW -> R.string.rn_err_workflow
                        StartResult.NO_DATASET -> R.string.rn_err_dataset
                        else -> R.string.rn_err_busy
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (run.active || run.finalStatus != null) {
            if (run.total > 0) {
                LinearProgressIndicator(progress = { (run.processed.toFloat() / run.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else if (run.active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            run.finalStatus?.let { Text(stringResource(R.string.rn_result, it), style = MaterialTheme.typography.titleSmall) }
            run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            val rt = Runtime.getRuntime()
            val memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.rn_s_total), "${run.processed}/${run.total}", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_ok), "${run.success}", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_fail), "${run.failed}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.rn_s_cpm), "${run.cpm}", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_avg), "${run.avgHttpMs} ms", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_time), mmss(run.elapsedMs), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(stringResource(R.string.rn_s_workers), "${run.activeWorkers}", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_mem), "$memMb MB", Modifier.weight(1f))
                Stat(stringResource(R.string.rn_s_state), if (run.paused) stringResource(R.string.rn_paused) else if (run.active) stringResource(R.string.rn_running) else "-", Modifier.weight(1f))
            }

            if (run.nodes.isNotEmpty()) {
                Text(stringResource(R.string.rn_nodes), style = MaterialTheme.typography.titleMedium)
                run.nodes.forEach { row ->
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
            if (run.logs.isNotEmpty()) {
                Text(stringResource(R.string.rn_logs), style = MaterialTheme.typography.titleMedium)
                run.logs.takeLast(40).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}
