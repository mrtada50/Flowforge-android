package com.flowforge.android.features.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flowforge.android.R
import com.flowforge.android.app.FlowForgeApp
import kotlinx.coroutines.launch

private fun currentLanguage(): String {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return when {
        tag.startsWith("ar") -> "ar"
        tag.startsWith("en") -> "en"
        else -> ""
    }
}

@Composable
fun SettingsScreen() {
    val container = (LocalContext.current.applicationContext as FlowForgeApp).container
    val repo = container.settingsRepository
    val saved by repo.allowlist.collectAsStateWithLifecycle()
    var text by remember(saved) { mutableStateOf(saved.joinToString("\n")) }
    var justSaved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(currentLanguage()) }
    val options = listOf("" to R.string.lang_system, "en" to R.string.lang_en, "ar" to R.string.lang_ar)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        options.forEach { (tag, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = selected == tag, role = Role.RadioButton,
                    onClick = {
                        selected = tag
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                    },
                ).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == tag, onClick = null)
                Text(stringResource(label), Modifier.padding(start = 12.dp))
            }
        }

        Text(stringResource(R.string.st_allowlist_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(stringResource(R.string.st_allowlist_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(text, { text = it; justSaved = false }, Modifier.fillMaxWidth(), minLines = 4)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { scope.launch { repo.saveAllowlist(text); justSaved = true } }) { Text(stringResource(R.string.st_save)) }
            if (justSaved) Text(stringResource(R.string.st_saved), color = MaterialTheme.colorScheme.secondary)
        }
    }
}
