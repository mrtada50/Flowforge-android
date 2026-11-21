package com.flowforge.android.data.repositories

import com.flowforge.android.data.database.ExecutionDao
import com.flowforge.android.data.database.ExecutionResultEntity
import com.flowforge.android.data.database.ExecutionSessionEntity
import com.flowforge.android.data.database.WorkflowConnectionEntity
import com.flowforge.android.data.database.WorkflowDao
import com.flowforge.android.data.database.WorkflowEntity
import com.flowforge.android.data.database.WorkflowNodeEntity
import com.flowforge.android.workflow.model.ConnectionDef
import com.flowforge.android.workflow.model.ExecutionSettings
import com.flowforge.android.workflow.model.NodeDef
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.WorkflowDef
import com.flowforge.android.workflow.model.newWorkflow
import com.flowforge.android.workflow.serialization.WorkflowJson
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

private fun encodeMap(m: Map<String, String>) = WorkflowJson.json.encodeToString(mapSerializer, m)
private fun decodeMap(s: String): Map<String, String> =
    runCatching { WorkflowJson.json.decodeFromString(mapSerializer, s) }.getOrDefault(emptyMap())

private fun decodeSettings(s: String): ExecutionSettings =
    runCatching { WorkflowJson.json.decodeFromString(ExecutionSettings.serializer(), s) }.getOrDefault(ExecutionSettings())

class WorkflowRepository(private val dao: WorkflowDao) {
    fun observeAll() = dao.observeAll()
    fun observeCount() = dao.observeCount()

    suspend fun load(id: String): WorkflowDef? {
        val w = dao.getWorkflow(id) ?: return null
        return WorkflowDef(
            schemaVersion = w.schemaVersion,
            id = w.id,
            name = w.name,
            nodes = dao.getNodes(id).map {
                NodeDef(it.id, NodeType.valueOf(it.type), it.displayName, it.x, it.y, decodeMap(it.configJson))
            },
            connections = dao.getConnections(id).map {
                ConnectionDef(it.id, it.fromNodeId, it.fromPort, it.toNodeId, it.toPort)
            },
            variables = decodeMap(w.variablesJson),
            settings = decodeSettings(w.settingsJson),
        )
    }

    /** Persists the whole workflow (metadata + graph) in one transaction. */
    suspend fun save(def: WorkflowDef) {
        val now = System.currentTimeMillis()
        val created = dao.getWorkflow(def.id)?.createdAt ?: now
        dao.saveGraph(
            WorkflowEntity(
                def.id, def.name, def.schemaVersion, created, now,
                encodeMap(def.variables),
                WorkflowJson.json.encodeToString(ExecutionSettings.serializer(), def.settings),
            ),
            def.nodes.map {
                WorkflowNodeEntity(it.id, def.id, it.type.name, it.name, it.x, it.y, encodeMap(it.config))
            },
            def.connections.map {
                WorkflowConnectionEntity(it.id, def.id, it.fromNode, it.fromPort, it.toNode, it.toPort)
            },
        )
    }

    suspend fun create(name: String): String {
        val def = newWorkflow(name)
        save(def)
        return def.id
    }

    suspend fun delete(id: String) = dao.deleteById(id)
}

class ExecutionRepository(private val dao: ExecutionDao) {
    fun observeSessionCount() = dao.observeSessionCount()
    fun observeResultCount() = dao.observeResultCount()
    fun observeResults(status: String?, query: String?, newest: Boolean, limit: Int): Flow<List<ExecutionResultEntity>> =
        dao.observeResults(status, query, if (newest) 1 else 0, limit)

    fun observeStatusCounts() = dao.observeStatusCounts()
    fun observeHttpCounts() = dao.observeHttpCounts()
    fun observeAvgHttpMs() = dao.observeAvgHttpMs()
    fun observeSessionStatusCounts() = dao.observeSessionStatusCounts()
    fun observeSessions(limit: Int) = dao.observeSessions(limit)
    suspend fun exportRows(limit: Int) = dao.exportRows(limit)

    suspend fun insertSession(session: ExecutionSessionEntity) = dao.insertSession(session)
    suspend fun updateSession(session: ExecutionSessionEntity) = dao.updateSession(session)
    suspend fun insertResults(results: List<ExecutionResultEntity>) {
        if (results.isNotEmpty()) dao.insertResults(results)
    }
}
