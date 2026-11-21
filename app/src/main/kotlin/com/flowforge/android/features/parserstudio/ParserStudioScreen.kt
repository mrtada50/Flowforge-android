package com.flowforge.android.features.parserstudio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flowforge.android.R
import com.flowforge.android.core.common.TextParsing
import com.flowforge.android.parser.ParseResult
import com.flowforge.android.parser.ParserEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Kind(val label: String) {
    JSON("JSON Path"), CSS("CSS"), REGEX("Regex"), HEADER("Header"), TEXT("Text between")
}

@Composable
fun ParserStudioScreen() {
    var kindName by rememberSaveable { mutableStateOf(Kind.JSON.name) }
    var input by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf("") }
    var selector by rememberSaveable { mutableStateOf("") }
    var attr by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var header by rememberSaveable { mutableStateOf("") }
    var left by rememberSaveable { mutableStateOf("") }
    var right by rememberSaveable { mutableStateOf("") }
    var variable by rememberSaveable { mutableStateOf("parsed") }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    val scope = rememberCoroutineScope()
    val kind = Kind.valueOf(kindName)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.ps_kind), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kindName = k.name; result = null }, label = { Text(k.label) })
            }
        }
        OutlinedTextField(
            input, { input = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.ps_input)) }, minLines = 5,
        )
        when (kind) {
            Kind.JSON -> Field(path, { path = it }, stringResource(R.string.ps_path))
            Kind.CSS -> {
                Field(selector, { selector = it }, stringResource(R.string.ps_selector))
                Field(attr, { attr = it }, stringResource(R.string.ps_attr))
            }
            Kind.REGEX -> {
                Field(pattern, { pattern = it }, stringResource(R.string.ps_pattern))
                Field(group, { group = it }, stringResource(R.string.ps_group))
            }
            Kind.HEADER -> Field(header, { header = it }, stringResource(R.string.ps_header))
            Kind.TEXT -> {
                Field(left, { left = it }, stringResource(R.string.ps_left))
                Field(right, { right = it }, stringResource(R.string.ps_right))
            }
        }
        Field(variable, { variable = it }, stringResource(R.string.ps_variable))
        Button(onClick = {
            val text = input
            scope.launch {
                result = withContext(Dispatchers.Default) {
                    when (kind) {
                        Kind.JSON -> ParserEngine.jsonPath(text, path)
                        Kind.CSS -> ParserEngine.cssSelector(text, selector, attr)
                        Kind.REGEX -> ParserEngine.regex(text, pattern, group.trim().toIntOrNull())
                        Kind.HEADER -> try {
                            ParserEngine.header(TextParsing.headers(text), header)
                        } catch (e: IllegalArgumentException) {
                            ParseResult.Failure(e.message ?: "Invalid headers")
                        }
                        Kind.TEXT -> ParserEngine.between(text, left, right)
                    }
                }
            }
        }) { Text(stringResource(R.string.ps_test)) }

        when (val r = result) {
            null -> Unit
            is ParseResult.Failure -> Text(r.reason, color = MaterialTheme.colorScheme.error)
            is ParseResult.Success -> {
                Text(stringResource(R.string.ps_result), style = MaterialTheme.typography.titleMedium)
                r.values.take(200).forEachIndexed { i, v ->
                    Text("${i + 1}. $v", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    stringResource(R.string.ps_assign, variable.ifBlank { "parsed" }, r.first),
                    color = MaterialTheme.colorScheme.secondary,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Text(stringResource(R.string.ps_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}
