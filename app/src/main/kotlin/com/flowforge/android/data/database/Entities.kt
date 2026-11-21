package com.flowforge.android.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val variablesJson: String,
    val settingsJson: String,
)

@Entity(
    tableName = "workflow_nodes",
    foreignKeys = [ForeignKey(WorkflowEntity::class, ["id"], ["workflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workflowId")],
)
data class WorkflowNodeEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val type: String,
    val displayName: String,
    val x: Float,
    val y: Float,
    val configJson: String,
)

@Entity(
    tableName = "workflow_connections",
    foreignKeys = [ForeignKey(WorkflowEntity::class, ["id"], ["workflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workflowId")],
)
data class WorkflowConnectionEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val fromNodeId: String,
    val fromPort: String,
    val toNodeId: String,
    val toPort: String,
)

@Entity(
    tableName = "execution_sessions",
    foreignKeys = [ForeignKey(WorkflowEntity::class, ["id"], ["workflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workflowId")],
)
data class ExecutionSessionEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
)

@Entity(
    tableName = "execution_results",
    foreignKeys = [ForeignKey(ExecutionSessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("workflowId")],
)
data class ExecutionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val workflowId: String,
    val nodeId: String,
    val timestamp: Long,
    val httpStatus: Int?,
    val durationMs: Long,
    val status: String,
    val errorDetails: String?,
    /** Redacted, size-limited response preview. */
    val preview: String?,
)

@Entity(tableName = "datasets")
data class DatasetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val format: String,
    val sizeBytes: Long,
    val lineCount: Long,
    val uri: String,
    val createdAt: Long,
)

@Entity(tableName = "proxy_profiles")
data class ProxyProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String?,
    /** Alias of the credential stored in Android Keystore-backed storage. Never the password itself. */
    val credentialAlias: String?,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
