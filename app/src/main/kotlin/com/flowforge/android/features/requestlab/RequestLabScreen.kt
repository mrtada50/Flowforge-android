package com.flowforge.android.features.requestlab

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import com.flowforge.android.core.common.TextParsing
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.BodyType
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult
import com.flowforge.android.network.HttpEngine
import com.flowforge.android.network.HttpRequestPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RequestLabState(
    val method: String = "GET",
    val url: String = "",
    val headers: String = "",
    val query: String = "",
    val bodyType: BodyType = BodyType.NONE,
    val body: String = "",
    val sending: Boolean = false,
    val preview: String? = null,
    val result: HttpResult? = null,
    val error: String? = null,
    val blocked: Boolean = false,
)

class RequestLabViewModel(private val http: HttpClientPort) : ViewModel() {
    private val _ui = MutableStateFlow(RequestLabState())
    val ui: StateFlow<RequestLabState> = _ui.asStateFlow()
    private var job: Job? = null

    fun update(f: RequestLabState.() -> RequestLabState) = _ui.update { it.f() }

    private fun buildSpec(s: RequestLabState): HttpRequestSpec {
        require(s.url.isNotBlank()) { "URL is required" }
        val form = s.bodyType == BodyType.FORM || s.bodyType == BodyType.URL_ENCODED
        return HttpRequestSpec(
            method = s.method,
            url = s.url.trim(),
            headers = TextParsing.headers(s.headers),
            query = TextParsing.pairs(s.query),
            bodyType = s.bodyType,
            body = s.body,
            fields = if (form) TextParsing.pairs(s.body) else emptyList(),
        )
    }

    fun preview() {
        try {
            _ui.update { it.copy(preview = HttpRequestPreview.build(buildSpec(it)), error = null, blocked = false) }
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(error = e.message, preview = null) }
        }
    }

    fun send() {
        val s = _ui.value
        if (s.sending) return
        val spec = try {
            buildSpec(s)
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(error = e.message, result = null) }
            return
        }
        _ui.update { it.copy(sending = true, error = null, blocked = false, result = null, preview = HttpRequestPreview.build(spec)) }
        job = viewModelScope.launch {
            try {
                val r = http.execute(spec)
                _ui.update { it.copy(result = r) }
            } catch (e: HttpEngineException) {
                _ui.update { it.copy(error = "${e.kind}: ${e.message}", blocked = e.kind == HttpEngineException.Kind.BLOCKED) }
            } finally {
                _ui.update { it.copy(sending = false) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

@Composable
fun RequestLabScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val vm: RequestLabViewModel = viewModel(factory = viewModelFactory { initializer { RequestLabViewModel(container.httpEngine) } })
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.rl_method), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").forEach { m ->
                FilterChip(selected = ui.method == m, onClick = { vm.update { copy(method = m) } }, label = { Text(m) })
            }
        }
        OutlinedTextField(
            ui.url, { v -> vm.update { copy(url = v) } }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rl_url)) }, singleLine = true,
        )
        OutlinedTextField(
            ui.headers, { v -> vm.update { copy(headers = v) } }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rl_headers)) }, minLines = 2,
        )
        OutlinedTextField(
            ui.query, { v -> vm.update { copy(query = v) } }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rl_query)) }, minLines = 2,
        )
        Text(stringResource(R.string.rl_body_type), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyType.entries.forEach { t ->
                FilterChip(selected = ui.bodyType == t, onClick = { vm.update { copy(bodyType = t) } }, label = { Text(t.name) })
            }
        }
        if (ui.bodyType != BodyType.NONE) {
            OutlinedTextField(
                ui.body, { v -> vm.update { copy(body = v) } }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rl_body)) }, minLines = 3,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.sending) {
                OutlinedButton(onClick = vm::cancel) { Text(stringResource(R.string.rl_cancel)) }
                CircularProgressIndicator(Modifier.padding(start = 4.dp))
            } else {
                Button(onClick = vm::send) { Text(stringResource(R.string.rl_send)) }
                OutlinedButton(onClick = vm::preview) { Text(stringResource(R.string.rl_preview)) }
            }
        }

        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            if (ui.blocked) Text(stringResource(R.string.rl_allowlist_hint), style = MaterialTheme.typography.bodySmall)
        }
        ui.preview?.let { Mono(stringResource(R.string.rl_preview_title), it) }
        ui.result?.let { r ->
            Text(stringResource(R.string.rl_response), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rl_status, r.status, r.durationMs))
            if (r.bodyTruncated) Text(stringResource(R.string.rl_truncated), color = MaterialTheme.colorScheme.error)
            Mono(stringResource(R.string.rl_headers_title), r.headers.joinToString("\n") { "${it.first}: ${it.second}" })
            val shown = if (r.body.length > 100_000) r.body.take(100_000) + "\n…" else r.body
            Mono(stringResource(R.string.rl_body_title), shown)
        }
    }
}

@Composable
internal fun Mono(title: String, text: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
