package com.flowforge.android.features.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.AppContainer
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.app.RunState
import com.flowforge.android.data.database.SessionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val workflowCount = c.workflowRepository.observeCount().state(0)
    val sessionCount = c.executionRepository.observeSessionCount().state(0)
    val resultCount = c.executionRepository.observeResultCount().state(0)
    val recent = c.executionRepository.observeSessions(5).state(emptyList<SessionRow>())
    val allowlistCount = c.settingsRepository.allowlist.map { it.size }.state(1)
    val runState: StateFlow<RunState> = c.runManager.state

    fun stopRun() = c.runManager.stop()
}

@Composable
fun DashboardScreen(onNavigate: (String) -> Unit) {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(container) } })
    val workflows by vm.workflowCount.collectAsStateWithLifecycle()
    val sessions by vm.sessionCount.collectAsStateWithLifecycle()
    val results by vm.resultCount.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val allowCount by vm.allowlistCount.collectAsStateWithLifecycle()
    val run by vm.runState.collectAsStateWithLifecycle()
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (allowCount == 0) {
            Card(
                Modifier.fillMaxWidth().clickable { onNavigate("settings") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    stringResource(R.string.dash_allowlist_empty),
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (run.active) {
            Card(Modifier.fillMaxWidth().clickable { onNavigate("runner") }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.dash_running), style = MaterialTheme.typography.labelLarge)
                    Text(run.workflowName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.rn_progress, run.processed, run.total, run.success, run.failed))
                    OutlinedButton(onClick = vm::stopRun) { Text(stringResource(R.string.rn_stop)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(stringResource(R.string.dash_workflows), workflows, Modifier.weight(1f))
            StatCard(stringResource(R.string.dash_sessions), sessions, Modifier.weight(1f))
            StatCard(stringResource(R.string.dash_results), results, Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onNavigate("workflow_studio") }) { Text(stringResource(R.string.nav_workflow_studio)) }
            OutlinedButton(onClick = { onNavigate("runner") }) { Text(stringResource(R.string.nav_runner)) }
            OutlinedButton(onClick = { onNavigate("request_lab") }) { Text(stringResource(R.string.nav_request_lab)) }
            OutlinedButton(onClick = { onNavigate("data_manager") }) { Text(stringResource(R.string.nav_data_manager)) }
        }
        Text(stringResource(R.string.dash_recent), style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) {
            Text(stringResource(R.string.dash_no_runs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.forEach { s ->
            Card(Modifier.fillMaxWidth().clickable { onNavigate("results") }) {
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
private fun StatCard(label: String, value: Int, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        }
    }
}
