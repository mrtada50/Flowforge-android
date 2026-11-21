package com.flowforge.android.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT COUNT(*) FROM workflows")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT * FROM workflows WHERE id = :id")
    abstract suspend fun getWorkflow(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflow_nodes WHERE workflowId = :id")
    abstract suspend fun getNodes(id: String): List<WorkflowNodeEntity>

    @Query("SELECT * FROM workflow_connections WHERE workflowId = :id")
    abstract suspend fun getConnections(id: String): List<WorkflowConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertWorkflow(workflow: WorkflowEntity)

    @Update
    abstract suspend fun updateWorkflow(workflow: WorkflowEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertNodes(nodes: List<WorkflowNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertConnections(connections: List<WorkflowConnectionEntity>)

    @Query("DELETE FROM workflow_nodes WHERE workflowId = :id")
    abstract suspend fun deleteNodes(id: String)

    @Query("DELETE FROM workflow_connections WHERE workflowId = :id")
    abstract suspend fun deleteConnections(id: String)

    @Query("DELETE FROM workflows WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    /** Insert-or-update the workflow row and replace its whole graph atomically. */
    @Transaction
    open suspend fun saveGraph(
        workflow: WorkflowEntity,
        nodes: List<WorkflowNodeEntity>,
        connections: List<WorkflowConnectionEntity>,
    ) {
        if (getWorkflow(workflow.id) == null) insertWorkflow(workflow) else updateWorkflow(workflow)
        deleteNodes(workflow.id)
        deleteConnections(workflow.id)
        insertNodes(nodes)
        insertConnections(connections)
    }
}

data class LabelCount(val label: String, val cnt: Int)

data class SessionRow(
    val id: String,
    val workflowId: String,
    val workflowName: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    val okCount: Int,
    val failCount: Int,
)

@Dao
abstract class ExecutionDao {
    @Query("SELECT COUNT(*) FROM execution_sessions")
    abstract fun observeSessionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM execution_results")
    abstract fun observeResultCount(): Flow<Int>

    @Query(
        "SELECT * FROM execution_results WHERE (:status IS NULL OR status = :status) " +
            "AND (:query IS NULL OR errorDetails LIKE '%' || :query || '%' OR nodeId LIKE '%' || :query || '%' " +
            "OR CAST(httpStatus AS TEXT) = :query) " +
            "ORDER BY CASE WHEN :newest = 1 THEN timestamp END DESC, " +
            "CASE WHEN :newest = 0 THEN timestamp END ASC, id DESC LIMIT :limit",
    )
    abstract fun observeResults(status: String?, query: String?, newest: Int, limit: Int): Flow<List<ExecutionResultEntity>>

    @Query("SELECT * FROM execution_results ORDER BY timestamp ASC, id ASC LIMIT :limit")
    abstract suspend fun exportRows(limit: Int): List<ExecutionResultEntity>

    @Query("SELECT status AS label, COUNT(*) AS cnt FROM execution_results GROUP BY status")
    abstract fun observeStatusCounts(): Flow<List<LabelCount>>

    @Query(
        "SELECT CAST(httpStatus AS TEXT) AS label, COUNT(*) AS cnt FROM execution_results " +
            "WHERE httpStatus IS NOT NULL GROUP BY httpStatus ORDER BY httpStatus",
    )
    abstract fun observeHttpCounts(): Flow<List<LabelCount>>

    @Query("SELECT AVG(durationMs) FROM execution_results WHERE httpStatus IS NOT NULL")
    abstract fun observeAvgHttpMs(): Flow<Double?>

    @Query("SELECT status AS label, COUNT(*) AS cnt FROM execution_sessions GROUP BY status")
    abstract fun observeSessionStatusCounts(): Flow<List<LabelCount>>

    @Query(
        "SELECT s.id AS id, s.workflowId AS workflowId, w.name AS workflowName, s.startedAt AS startedAt, " +
            "s.finishedAt AS finishedAt, s.status AS status, " +
            "(SELECT COUNT(*) FROM execution_results r WHERE r.sessionId = s.id AND r.status = 'SUCCESS') AS okCount, " +
            "(SELECT COUNT(*) FROM execution_results r WHERE r.sessionId = s.id AND r.status = 'FAILED') AS failCount " +
            "FROM execution_sessions s LEFT JOIN workflows w ON w.id = s.workflowId " +
            "ORDER BY s.startedAt DESC LIMIT :limit",
    )
    abstract fun observeSessions(limit: Int): Flow<List<SessionRow>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSession(session: ExecutionSessionEntity)

    @Update
    abstract suspend fun updateSession(session: ExecutionSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertResults(results: List<ExecutionResultEntity>)
}

@Dao
abstract class DatasetDao {
    @Query("SELECT * FROM datasets ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<DatasetEntity>>

    @Query("SELECT * FROM datasets WHERE id = :id")
    abstract suspend fun get(id: String): DatasetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(dataset: DatasetEntity)

    @Query("DELETE FROM datasets WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Dao
abstract class ProxyDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY name")
    abstract fun observeAll(): Flow<List<ProxyProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(profile: ProxyProfileEntity)

    @Query("DELETE FROM proxy_profiles WHERE id = :id")
    abstract suspend fun delete(id: String)
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)
}
