package com.flowforge.android.app

import android.content.Context
import com.flowforge.android.core.security.DomainAllowlist
import com.flowforge.android.core.security.SecretStore
import com.flowforge.android.data.database.FlowForgeDatabase
import com.flowforge.android.data.repositories.DatasetRepository
import com.flowforge.android.data.repositories.ExecutionRepository
import com.flowforge.android.data.repositories.ProxyRepository
import com.flowforge.android.data.repositories.SettingsRepository
import com.flowforge.android.data.repositories.WorkflowRepository
import com.flowforge.android.network.HttpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Manual dependency container (no DI framework, to keep the build simple and predictable). */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db: FlowForgeDatabase = FlowForgeDatabase.build(context)

    val workflowRepository = WorkflowRepository(db.workflowDao())
    val executionRepository = ExecutionRepository(db.executionDao())
    val settingsRepository = SettingsRepository(db.settingsDao(), appScope)
    val datasetRepository = DatasetRepository(db.datasetDao(), File(context.filesDir, "datasets"))
    val proxyRepository = ProxyRepository(db.proxyDao(), db.settingsDao(), SecretStore(context), appScope)

    /** Shared engine: one cookie session, allowlist and active proxy read live from settings. */
    val httpEngine = HttpEngine(
        allowlistProvider = { DomainAllowlist(settingsRepository.allowlist.value) },
        proxyProvider = { proxyRepository.activeConfig.value },
    )

    val runManager = RunManager(workflowRepository, executionRepository, datasetRepository, httpEngine, appScope)
}
