package com.flowforge.android.app

import android.content.Context
import com.flowforge.android.core.security.DomainAllowlist
import com.flowforge.android.data.database.FlowForgeDatabase
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.repositories.SettingsRepository
import com.flowforge.android.data.repositories.WorkflowRepository
import com.flowforge.android.network.HttpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework, to keep the build simple and predictable). */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db: FlowForgeDatabase = FlowForgeDatabase.build(context)

    val workflowRepository = WorkflowRepository(db.workflowDao())
    val executionRepository = ExecutionRepository(db.executionDao())
    val settingsRepository = SettingsRepository(db.settingsDao(), appScope)

    /** Shared engine: one cookie session, allowlist read live from settings. */
    val httpEngine = HttpEngine(allowlistProvider = { DomainAllowlist(settingsRepository.allowlist.value) })
}
