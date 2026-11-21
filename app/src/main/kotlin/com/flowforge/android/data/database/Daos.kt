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

@Dao
abstract class ExecutionDao {
    @Query("SELECT COUNT(*) FROM execution_sessions")
    abstract fun observeSessionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM execution_results")
    abstract fun observeResultCount(): Flow<Int>

    @Query(
        "SELECT * FROM execution_results WHERE (:status IS NULL OR status = :status) " +
            "ORDER BY timestamp DESC, id DESC LIMIT :limit",
    )
    abstract fun observeResults(status: String?, limit: Int): Flow<List<ExecutionResultEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSession(session: ExecutionSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertResults(results: List<ExecutionResultEntity>)

    @Transaction
    open suspend fun saveRun(session: ExecutionSessionEntity, results: List<ExecutionResultEntity>) {
        insertSession(session)
        insertResults(results)
    }
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)
}
