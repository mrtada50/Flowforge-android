package com.flowforge.android.features.results

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.flowforge.android.data.database.LabelCount
import com.flowforge.android.data.database.SessionRow
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.storage.ResultsCsv
import com.flowforge.android.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date

private data class Params(val status: String?, val query: String, val newest: Boolean, val limit: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(private val repo: ExecutionRepository) : ViewModel() {
    val filter = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val newest = MutableStateFlow(true)
    private val limit = MutableStateFlow(PAGE)

    val results: StateFlow<List<ExecutionResultEntity>?> =
        combine(filter, query, newest, limit) { f, q, n, l -> Params(f, q, n, l) }
            .flatMapLatest { p -> repo.observeResults(p.status, p.query.trim().takeIf { it.isNotEmpty() }, p.newest, p.limit) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pageSize: Int get() = limit.value

    val statusCounts: StateFlow<List<LabelCount>> = repo.observeStatusCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val httpCounts: StateFlow<List<LabelCount>> = repo.observeHttpCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessionCounts: StateFlow<List<LabelCount>> = repo.observeSessionStatusCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val avgMs: StateFlow<Double?> = repo.observeAvgHttpMs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sessions: StateFlow<List<SessionRow>> = repo.observeSessions(20).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exported = MutableStateFlow<Int?>(null)
    val exported: StateFlow<Int?> = _exported.asStateFlow()
    fun clearExported() { _exported.value = null }

    fun setFilter(status: String?) { filter.value = status; limit.value = PAGE }
    fun setQuery(q: String) { query.value = q; limit.value = PAGE }
    fun setNewest(v: Boolean) { newest.value = v }
    fun loadMore() = limit.update { it + PAGE }

    fun exportCsv(open: () -> OutputStream?) {
        viewModelScope.launch {
            try {
                val rows = repo.exportRows(100_000)
                val text = ResultsCsv.build(rows)
                withContext(Dispatchers.IO) {
                    val out = open() ?: throw IllegalStateException("Cannot write the file")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                _exported.value = rows.size
            } catch (e: Exception) {
                _exported.value = -1
            }
        }
    }

    companion object { const val PAGE = 50 }
}

@Composable
fun ResultsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowForgeApp).container
    val vm: ResultsViewModel = viewModel(factory = viewModelFactory { initializer { ResultsViewModel(container.executionRepository) } })
    var tab by rememberSaveable { mutableStateOf(0) }
    val exported by vm.exported.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv { context.contentResolver.openOutputStream(uri, "wt") }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.re_tab_results)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.re_tab_analytics)) })
        }
        if (tab == 0) ResultsList(vm) { exportLauncher.launch("flowforge-results.csv") } else Analytics(vm)
    }

    exported?.let { n ->
        AlertDialog(
            onDismissRequest = vm::clearExported,
            text = { Text(if (n >= 0) stringResource(R.string.re_exported, n) else stringResource(R.string.ed_export_failed)) },
            confirmButton = { TextButton(onClick = vm::clearExported) { Text(stringResource(R.string.re_close)) } },
        )
    }
}

@Composable
private fun ResultsList(vm: ResultsViewModel, onExport: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val newest by vm.newest.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ExecutionResultEntity?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, vm::setQuery, Modifier.fillMaxWidth().padding(12.dp, 8.dp, 12.dp, 0.dp), singleLine = true,
            label = { Text(stringResource(R.string.re_search)) },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { vm.setFilter(null) }, label = { Text(stringResource(R.string.re_all)) })
            listOf("SUCCESS", "FAILED", "SKIPPED", "CANCELLED").forEach { s ->
                FilterChip(selected = filter == s, onClick = { vm.setFilter(s) }, label = { Text(s) })
            }
            FilterChip(selected = newest, onClick = { vm.setNewest(true) }, label = { Text(stringResource(R.string.re_newest)) })
            FilterChip(selected = !newest, onClick = { vm.setNewest(false) }, label = { Text(stringResource(R.string.re_oldest)) })
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.re_export)) }
        }
        val list = results
        when {
            list == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> Box(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.re_empty), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { detail = r }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(StatusColors.forStatus(r.status))
                                Text(
                                    r.status, Modifier.weight(1f).padding(start = 6.dp),
                                    style = MaterialTheme.typography.titleSmall, color = StatusColors.forStatus(r.status),
                                )
                                Text(fmt.format(Date(r.timestamp)), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                listOfNotNull(r.httpStatus?.let { "HTTP $it" }, "${r.durationMs} ms", r.nodeId.take(12)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            r.errorDetails?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (list.size >= vm.pageSize) {
                    item {
                        OutlinedButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.re_load_more)) }
                    }
                }
            }
        }
    }

    detail?.let { r ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(stringResource(R.string.re_details)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status: ${r.status}")
                    Text("Execution: ${r.sessionId}", style = MaterialTheme.typography.bodySmall)
                    Text("Workflow: ${r.workflowId}", style = MaterialTheme.typography.bodySmall)
                    Text("Node: ${r.nodeId}", style = MaterialTheme.typography.bodySmall)
                    Text("HTTP: ${r.httpStatus ?: "-"} · ${r.durationMs} ms", style = MaterialTheme.typography.bodySmall)
                    r.errorDetails?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    r.preview?.let {
                        Text(stringResource(R.string.re_raw), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.re_close)) } },
        )
    }
}

@Composable
private fun Analytics(vm: ResultsViewModel) {
    val sessionCounts by vm.sessionCounts.collectAsStateWithLifecycle()
    val httpCounts by vm.httpCounts.collectAsStateWithLifecycle()
    val statusCounts by vm.statusCounts.collectAsStateWithLifecycle()
    val avg by vm.avgMs.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    val total = sessionCounts.sumOf { it.cnt }
    val completed = sessionCounts.filter { it.label == "COMPLETED" }.sumOf { it.cnt }
    val ignored = sessionCounts.filter { it.label == "CANCELLED" || it.label == "RUNNING" }.sumOf { it.cnt }
    val failed = total - completed - ignored
    val finished = completed + failed
    val successRate = if (finished > 0) completed * 100.0 / finished else 0.0
    val failureRate = if (finished > 0) failed * 100.0 / finished else 0.0

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(R.string.an_total), "$total", Modifier.weight(1f))
            Metric(stringResource(R.string.an_ok), "$completed", Modifier.weight(1f))
            Metric(stringResource(R.string.an_fail), "$failed", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(R.string.an_success_rate), "%.1f%%".format(successRate), Modifier.weight(1f))
            Metric(stringResource(R.string.an_failure_rate), "%.1f%%".format(failureRate), Modifier.weight(1f))
            Metric(stringResource(R.string.an_avg), avg?.let { "%.0f ms".format(it) } ?: "-", Modifier.weight(1f))
        }

        Text(stringResource(R.string.an_http), style = MaterialTheme.typography.titleMedium)
        if (httpCounts.isEmpty()) Text(stringResource(R.string.an_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val maxHttp = (httpCounts.maxOfOrNull { it.cnt } ?: 1).coerceAtLeast(1)
        httpCounts.forEach { Bar(it.label, it.cnt, it.cnt.toFloat() / maxHttp, httpColor(it.label)) }

        Text(stringResource(R.string.an_status), style = MaterialTheme.typography.titleMedium)
        val maxStatus = (statusCounts.maxOfOrNull { it.cnt } ?: 1).coerceAtLeast(1)
        statusCounts.forEach { Bar(it.label, it.cnt, it.cnt.toFloat() / maxStatus, StatusColors.forStatus(it.label)) }

        Text(stringResource(R.string.an_history), style = MaterialTheme.typography.titleMedium)
        if (sessions.isEmpty()) Text(stringResource(R.string.an_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        sessions.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(s.workflowName ?: "-", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(s.status, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        fmt.format(Date(s.startedAt)) + " · ✔ ${s.okCount} · ✖ ${s.failCount}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color),
    )
}

private fun httpColor(label: String) = StatusColors.forHttp(label.toIntOrNull() ?: 0)

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Bar(label: String, count: Int, fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Box(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(12.dp).background(color))
        }
        Text("$count", style = MaterialTheme.typography.bodySmall)
    }
}
