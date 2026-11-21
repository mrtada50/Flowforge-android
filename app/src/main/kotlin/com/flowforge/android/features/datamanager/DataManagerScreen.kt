package com.flowforge.android.features.datamanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.flowforge.android.data.database.DatasetEntity
import com.flowforge.android.data.repositories.DatasetRepository
import com.flowforge.android.data.storage.DuplicateReport
import com.flowforge.android.data.storage.ValidationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

data class PreviewState(
    val dataset: DatasetEntity,
    val lines: List<String>,
    val query: String = "",
    val hits: List<Pair<Long, String>>? = null,
)

data class CheckState(val dataset: DatasetEntity, val duplicates: DuplicateReport, val validation: ValidationReport)

data class DataUi(
    val busy: Boolean = false,
    val message: String? = null,
    val preview: PreviewState? = null,
    val check: CheckState? = null,
)

class DataManagerViewModel(private val repo: DatasetRepository) : ViewModel() {
    val datasets: StateFlow<List<DatasetEntity>?> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _ui = MutableStateFlow(DataUi())
    val ui: StateFlow<DataUi> = _ui.asStateFlow()

    private fun fail(e: Throwable) = _ui.update { it.copy(busy = false, message = e.message ?: e::class.java.simpleName) }

    fun clearMessage() = _ui.update { it.copy(message = null) }
    fun closePreview() = _ui.update { it.copy(preview = null) }
    fun closeCheck() = _ui.update { it.copy(check = null) }

    fun importFile(name: String, format: String, open: () -> InputStream?) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val stream = open() ?: throw IllegalStateException("Cannot open the file")
                stream.use { repo.import(name, format, it) }
                _ui.update { it.copy(busy = false) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun export(ds: DatasetEntity, open: () -> OutputStream?) {
        viewModelScope.launch {
            try {
                val out = open() ?: throw IllegalStateException("Cannot write the file")
                out.use { repo.exportTo(ds, it) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun delete(ds: DatasetEntity) {
        viewModelScope.launch { repo.delete(ds) }
    }

    fun openPreview(ds: DatasetEntity) {
        viewModelScope.launch {
            try {
                _ui.update { it.copy(preview = PreviewState(ds, repo.preview(ds, 50))) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun search(query: String) {
        val p = _ui.value.preview ?: return
        viewModelScope.launch {
            try {
                val hits = if (query.isBlank()) null else repo.search(p.dataset, query)
                _ui.update { it.copy(preview = it.preview?.copy(query = query, hits = hits)) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun runCheck(ds: DatasetEntity) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try {
                val d = repo.duplicates(ds)
                val v = repo.validate(ds)
                _ui.update { it.copy(busy = false, check = CheckState(ds, d, v)) }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: "dataset"

private fun formatOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "csv" -> "csv"
    "json" -> "json"
    else -> "txt"
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun DataManagerScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as FlowForgeApp).container
    val vm: DataManagerViewModel = viewModel(factory = viewModelFactory { initializer { DataManagerViewModel(container.datasetRepository) } })
    val list by vm.datasets.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<DatasetEntity?>(null) }
    var exportTarget by remember { mutableStateOf<DatasetEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = displayName(context, uri)
            vm.importFile(name, formatOf(name)) { context.contentResolver.openInputStream(uri) }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val ds = exportTarget
        if (uri != null && ds != null) vm.export(ds) { context.contentResolver.openOutputStream(uri, "wt") }
        exportTarget = null
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dm_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { importLauncher.launch(arrayOf("text/plain", "text/csv", "application/json", "application/octet-stream")) }) {
                    Text(stringResource(R.string.dm_import))
                }
                if (ui.busy) CircularProgressIndicator()
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = list
            when {
                shown == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                shown.isEmpty() -> Text(
                    stringResource(R.string.dm_empty), Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { ds ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(ds.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.dm_info, ds.format.uppercase(), ds.lineCount, humanSize(ds.sizeBytes)),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { vm.openPreview(ds) }) { Text(stringResource(R.string.dm_preview)) }
                                    TextButton(onClick = { vm.runCheck(ds) }) { Text(stringResource(R.string.dm_check)) }
                                    TextButton(onClick = { exportTarget = ds; exportLauncher.launch(ds.name) }) { Text(stringResource(R.string.dm_export)) }
                                    TextButton(onClick = { pendingDelete = ds }) { Text(stringResource(R.string.ws_delete)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    ui.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text(stringResource(R.string.re_close)) } },
        )
    }

    pendingDelete?.let { ds ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dm_delete_title)) },
            text = { Text(stringResource(R.string.dm_delete_msg, ds.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(ds); pendingDelete = null }) { Text(stringResource(R.string.ws_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ws_cancel)) } },
        )
    }

    ui.preview?.let { p ->
        var query by remember(p.dataset.id) { mutableStateOf(p.query) }
        AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text(p.dataset.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.dm_search)) },
                    )
                    OutlinedButton(onClick = { vm.search(query) }) { Text(stringResource(R.string.dm_search_go)) }
                    SelectionContainer {
                        Column {
                            val hits = p.hits
                            if (hits != null) {
                                if (hits.isEmpty()) Text(stringResource(R.string.dm_no_hits))
                                hits.forEach { (n, line) -> Text("$n: $line", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            } else {
                                p.lines.forEachIndexed { i, line ->
                                    Text("${i + 1}: $line", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::closePreview) { Text(stringResource(R.string.re_close)) } },
        )
    }

    ui.check?.let { c ->
        AlertDialog(
            onDismissRequest = vm::closeCheck,
            title = { Text(c.dataset.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.dm_rep_lines, c.dataset.lineCount))
                    Text(stringResource(R.string.dm_rep_unique, c.duplicates.unique, c.duplicates.duplicates))
                    if (c.duplicates.truncated) Text(stringResource(R.string.dm_rep_truncated))
                    Text(stringResource(R.string.dm_rep_empty, c.validation.emptyLines))
                    Text(stringResource(R.string.dm_rep_long, c.validation.longLines))
                    if (c.dataset.format == "csv") Text(stringResource(R.string.dm_rep_cols, c.validation.inconsistentColumns))
                    c.validation.formatError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeCheck) { Text(stringResource(R.string.re_close)) } },
        )
    }
}
