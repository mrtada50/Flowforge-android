package com.flowforge.android.data.repositories

import com.flowforge.android.core.security.SecretStore
import com.flowforge.android.data.database.AppSettingEntity
import com.flowforge.android.data.database.ProxyDao
import com.flowforge.android.data.database.ProxyProfileEntity
import com.flowforge.android.data.database.SettingsDao
import com.flowforge.android.domain.models.ProxyConfig
import com.flowforge.android.domain.models.ProxyType
import com.flowforge.android.workflow.model.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class ProxyTestResult(val reachable: Boolean, val millis: Long, val error: String?)

/**
 * Proxy profiles. Exactly one profile can be "active" (or none). There is no rotation:
 * every request uses the active proxy, or none.
 */
class ProxyRepository(
    private val dao: ProxyDao,
    private val settings: SettingsDao,
    private val secrets: SecretStore,
    scope: CoroutineScope,
) {
    val profiles = dao.observeAll()

    val activeId: StateFlow<String?> = settings.observe(KEY_ACTIVE)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Resolved config (with decrypted password) used by the HTTP engine. */
    val activeConfig: StateFlow<ProxyConfig?> = combine(settings.observe(KEY_ACTIVE), dao.observeAll()) { id, list ->
        list.firstOrNull { it.id == id }?.let { toConfig(it) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun toConfig(p: ProxyProfileEntity) = ProxyConfig(
        type = runCatching { ProxyType.valueOf(p.type) }.getOrDefault(ProxyType.HTTP),
        host = p.host,
        port = p.port,
        username = p.username,
        password = p.credentialAlias?.let { secrets.get(it) },
    )

    suspend fun add(name: String, type: ProxyType, host: String, port: Int, username: String?, password: String?) {
        val id = newId()
        val user = username?.trim()?.takeIf { it.isNotEmpty() }
        val alias = if (user != null && !password.isNullOrEmpty()) id else null
        if (alias != null) secrets.put(alias, password!!)
        dao.upsert(ProxyProfileEntity(id, name.trim(), type.name, host.trim(), port, user, alias))
    }

    suspend fun delete(p: ProxyProfileEntity) {
        p.credentialAlias?.let { secrets.delete(it) }
        if (activeId.value == p.id) settings.put(AppSettingEntity(KEY_ACTIVE, ""))
        dao.delete(p.id)
    }

    suspend fun setActive(id: String?) = settings.put(AppSettingEntity(KEY_ACTIVE, id.orEmpty()))

    /** TCP reachability of the proxy only. This does NOT prove the target service is reachable through it. */
    suspend fun test(p: ProxyProfileEntity): ProxyTestResult = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        try {
            Socket().use { it.connect(InetSocketAddress(p.host, p.port), 5000) }
            ProxyTestResult(true, (System.nanoTime() - t0) / 1_000_000, null)
        } catch (e: Exception) {
            ProxyTestResult(false, (System.nanoTime() - t0) / 1_000_000, e.message ?: e::class.java.simpleName)
        }
    }

    companion object {
        const val KEY_ACTIVE = "active_proxy_id"
        private val hostRule = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        fun validHost(h: String) = hostRule.matches(h.trim())
        fun validPort(p: Int) = p in 1..65535
    }
}
