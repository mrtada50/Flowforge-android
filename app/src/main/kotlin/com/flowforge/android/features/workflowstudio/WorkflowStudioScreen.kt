package com.flowforge.android.features.workflowstudio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.data.database.WorkflowEntity
import com.flowforge.android.data.repositories.WorkflowRepository
import com.flowforge.android.workflow.model.withFreshIds
import com.flowforge.android.workflow.serialization.WorkflowJson
import com.flowforge.android.workflow.validation.WorkflowValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkflowListViewModel(private val repo: WorkflowRepository) : ViewModel() {
    /** null = still loading. */
    val workflows: StateFlow<List<WorkflowEntity>?> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.create(name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    /** Imports a workflow file as a NEW workflow. Invalid graphs are refused, not repaired. */
    fun import(text: String) {
        viewModelScope.launch {
            val decoded = WorkflowJson.decode(text)
            val def = decoded.getOrNull()
            if (def == null) {
                _importError.value = decoded.exceptionOrNull()?.message ?: "Invalid file"
                return@launch
            }
            val firstError = WorkflowValidator.validate(def).firstOrNull { it.severity == com.flowforge.android.workflow.validation.Severity.ERROR }
            if (firstError != null) {
                _importError.value = firstError.message
                return@launch
            }
            repo.save(def.withFreshIds())
        }
    }

    fun setImportError(message: String?) {
        _importError.value = message
    }
}

@Composable
fun WorkflowStudioScreen(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowForgeApp).container
    val vm: WorkflowListViewModel = viewModel(
        factory = viewModelFactory { initializer { WorkflowListViewModel(container.workflowRepository) } },
    )
    val workflows by vm.workflows.collectAsStateWithLifecycle()
    val importError by vm.importError.collectAsStateWithLifecycle()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<WorkflowEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { s ->
                            val bytes = s.readNBytes(WorkflowJson.MAX_IMPORT_CHARS + 1)
                            if (bytes.size > WorkflowJson.MAX_IMPORT_CHARS) error("File is too large")
                            String(bytes)
                        } ?: error("Cannot open file")
                    }
                }
                text.onSuccess { vm.import(it) }.onFailure { vm.setImportError(it.message) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        val list = workflows
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.isEmpty() -> Text(
                stringResource(R.string.ws_empty),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 72.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { wf ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(wf.id) }) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(wf.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { pendingDelete = wf }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ws_delete))
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        ) { Text(stringResource(R.string.ws_import)) }
        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ws_add))
        }
    }

    if (showCreate) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.ws_add)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ws_name)) }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.create(name); showCreate = false }) {
                    Text(stringResource(R.string.ws_create))
                }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.ws_cancel)) } },
        )
    }

    pendingDelete?.let { wf ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ws_delete_title)) },
            text = { Text(stringResource(R.string.ws_delete_msg, wf.name)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(wf.id); pendingDelete = null }) { Text(stringResource(R.string.ws_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ws_cancel)) } },
        )
    }

    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.setImportError(null) },
            text = { Text(stringResource(R.string.ws_import_failed, msg)) },
            confirmButton = { TextButton(onClick = { vm.setImportError(null) }) { Text(stringResource(R.string.re_close)) } },
        )
    }
}
