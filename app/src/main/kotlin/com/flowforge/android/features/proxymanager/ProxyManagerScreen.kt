package com.flowforge.android.features.proxymanager

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.data.database.ProxyProfileEntity
import com.flowforge.android.data.repositories.ProxyRepository
import com.flowforge.android.data.repositories.ProxyTestResult
import com.flowforge.android.domain.models.ProxyType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProxyManagerViewModel(private val repo: ProxyRepository) : ViewModel() {
    val profiles: StateFlow<List<ProxyProfileEntity>?> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val activeId: StateFlow<String?> = repo.activeId

    private val _tests = MutableStateFlow<Map<String, ProxyTestResult?>>(emptyMap())
    val tests: StateFlow<Map<String, ProxyTestResult?>> = _tests.asStateFlow()

    /** Returns an error message, or null when the profile was saved. */
    fun add(name: String, type: ProxyType, host: String, portText: String, user: String, pass: String): String? {
        val port = portText.trim().toIntOrNull()
        if (name.isBlank()) return "Name is required"
        if (!ProxyRepository.validHost(host)) return "Invalid host"
        if (port == null || !ProxyRepository.validPort(port)) return "Port must be 1-65535"
        if (type == ProxyType.SOCKS5 && user.isNotBlank()) return "SOCKS5 authentication is not supported"
        viewModelScope.launch { repo.add(name, type, host, port, user, pass) }
        return null
    }

    fun delete(p: ProxyProfileEntity) {
        viewModelScope.launch { repo.delete(p) }
    }

    fun setActive(id: String?) {
        viewModelScope.launch { repo.setActive(id) }
    }

    fun test(p: ProxyProfileEntity) {
        _tests.update { it + (p.id to null) }
        viewModelScope.launch {
            val r = repo.test(p)
            _tests.update { it + (p.id to r) }
        }
    }
}

@Composable
fun ProxyManagerScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: ProxyManagerViewModel = viewModel(factory = viewModelFactory { initializer { ProxyManagerViewModel(container.proxyRepository) } })
    val list by vm.profiles.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val tests by vm.tests.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProxyProfileEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.px_notice),
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = list
            when {
                shown == null -> Box(Modifier.fillMaxWidth()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                shown.isEmpty() -> Text(
                    stringResource(R.string.px_empty), Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { p ->
                        val isActive = p.id == activeId
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row {
                                    Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (isActive) Text(stringResource(R.string.px_active), color = MaterialTheme.colorScheme.secondary)
                                }
                                Text(
                                    "${p.type.lowercase()}://${p.host}:${p.port}" +
                                        if (p.username != null) "  ·  ${stringResource(R.string.px_auth)}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (tests.containsKey(p.id)) {
                                    val r = tests[p.id]
                                    when {
                                        r == null -> CircularProgressIndicator()
                                        r.reachable -> Text(stringResource(R.string.px_reachable, r.millis), color = MaterialTheme.colorScheme.secondary)
                                        else -> Text(stringResource(R.string.px_unreachable, r.error ?: "-"), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { vm.test(p) }) { Text(stringResource(R.string.px_test)) }
                                    if (isActive) {
                                        TextButton(onClick = { vm.setActive(null) }) { Text(stringResource(R.string.px_deactivate)) }
                                    } else {
                                        TextButton(onClick = { vm.setActive(p.id) }) { Text(stringResource(R.string.px_use)) }
                                    }
                                    TextButton(onClick = { pendingDelete = p }) { Text(stringResource(R.string.ws_delete)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = { showAdd = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.px_add))
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(ProxyType.HTTP) }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.px_add)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.px_name)) })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProxyType.entries.forEach { t ->
                            FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name) })
                        }
                    }
                    OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.px_host)) })
                    OutlinedTextField(
                        port, { port = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.px_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.px_user)) })
                    OutlinedTextField(
                        pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.px_pass)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val e = vm.add(name, type, host, port, user, pass)
                    if (e == null) showAdd = false else error = e
                }) { Text(stringResource(R.string.ed_save)) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.ws_cancel)) } },
        )
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.px_delete_title)) },
            text = { Text(stringResource(R.string.px_delete_msg, p.name)) },
            confirmButton = { TextButton(onClick = { vm.delete(p); pendingDelete = null }) { Text(stringResource(R.string.ws_delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ws_cancel)) } },
        )
    }
}
