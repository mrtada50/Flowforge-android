package com.flowforge.android.data.repositories

import com.flowforge.android.data.database.AppSettingEntity
import com.flowforge.android.data.database.SettingsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SettingsRepository(private val dao: SettingsDao, scope: CoroutineScope) {
    /** Allowed hosts, one per entry. Empty = every request is refused. */
    val allowlist: StateFlow<List<String>> = dao.observe(KEY_ALLOWLIST)
        .map { parse(it.orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun saveAllowlist(text: String) {
        dao.put(AppSettingEntity(KEY_ALLOWLIST, parse(text).joinToString("\n")))
    }

    companion object {
        const val KEY_ALLOWLIST = "domain_allowlist"
        private val valid = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

        fun parse(text: String): List<String> =
            text.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() && valid.matches(it) }.distinct()
    }
}
