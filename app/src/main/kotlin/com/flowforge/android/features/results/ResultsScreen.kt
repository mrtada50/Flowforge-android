package com.flowforge.android.features.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.flowforge.android.data.repositories.ExecutionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModel(repo: ExecutionRepository) : ViewModel() {
    val filter = MutableStateFlow<String?>(null)
    private val limit = MutableStateFlow(PAGE)

    val results: StateFlow<List<ExecutionResultEntity>?> = combine(filter, limit) { f, l -> f to l }
        .flatMapLatest { (f, l) -> repo.observeResults(f, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val pageSize: Int get() = limit.value

    fun setFilter(status: String?) {
        filter.value = status
        limit.value = PAGE
    }

    fun loadMore() = limit.update { it + PAGE }

    companion object { const val PAGE = 50 }
}

@Composable
fun ResultsScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: ResultsViewModel = viewModel(factory = viewModelFactory { initializer { ResultsViewModel(container.executionRepository) } })
    val results by vm.results.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<ExecutionResultEntity?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { vm.setFilter(null) }, label = { Text(stringResource(R.string.re_all)) })
            listOf("SUCCESS", "FAILED", "SKIPPED", "CANCELLED").forEach { s ->
                FilterChip(selected = filter == s, onClick = { vm.setFilter(s) }, label = { Text(s) })
            }
        }
        val list = results
        when {
            list == null -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            list.isEmpty() -> Box(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.re_empty), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { detail = r }) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(r.status, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(fmt.format(Date(r.timestamp)), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(
                                listOfNotNull(r.httpStatus?.let { "HTTP $it" }, "${r.durationMs} ms").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            r.errorDetails?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (list.size >= vm.pageSize) {
                    item {
                        OutlinedButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.re_load_more))
                        }
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
