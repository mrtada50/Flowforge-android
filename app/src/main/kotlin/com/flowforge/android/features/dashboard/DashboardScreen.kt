package com.flowforge.android.features.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.repositories.WorkflowRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DashboardViewModel(workflows: WorkflowRepository, executions: ExecutionRepository) : ViewModel() {
    private fun Flow<Int>.state(): StateFlow<Int> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val workflowCount = workflows.observeCount().state()
    val sessionCount = executions.observeSessionCount().state()
    val resultCount = executions.observeResultCount().state()
}

@Composable
fun DashboardScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: DashboardViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DashboardViewModel(container.workflowRepository, container.executionRepository) }
        },
    )
    val workflows by vm.workflowCount.collectAsStateWithLifecycle()
    val sessions by vm.sessionCount.collectAsStateWithLifecycle()
    val results by vm.resultCount.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(stringResource(R.string.dash_workflows), workflows)
        StatCard(stringResource(R.string.dash_sessions), sessions)
        StatCard(stringResource(R.string.dash_results), results)
    }
}

@Composable
private fun StatCard(label: String, value: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.headlineMedium)
        }
    }
}
