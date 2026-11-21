package com.flowforge.android.workflow.model

import kotlinx.serialization.Serializable
import java.util.UUID

const val CURRENT_SCHEMA_VERSION = 1

@Serializable
enum class NodeType(val title: String) {
    START("Start"),
    END("End"),
    HTTP_REQUEST("HTTP Request"),
    SET_VARIABLE("Set Variable"),
    CONDITION("Condition"),
    DELAY("Delay"),
    LOGGER("Logger"),
    JSON_PARSER("JSON Parser"),
    HTML_PARSER("HTML Parser"),
    REGEX_PARSER("Regex Parser"),
    HEADER_PARSER("Header Parser"),
    TEXT_EXTRACTOR("Text Extractor"),
    INPUT_DATASET("Input Dataset"),
    DATA_TRANSFORM("Data Transform"),
    DATA_VALIDATION("Data Validation"),
    LOOP("Controlled Loop"),
    IF_ELSE("If / Else"),
    SUCCESS_BRANCH("Success Branch"),
    ERROR_BRANCH("Error Branch"),
    STOP("Stop Execution"),
}

@Serializable
data class NodeDef(
    val id: String,
    val type: NodeType,
    val name: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val config: Map<String, String> = emptyMap(),
)

@Serializable
data class ConnectionDef(
    val id: String,
    val fromNode: String,
    val fromPort: String,
    val toNode: String,
    val toPort: String,
)

@Serializable
data class ExecutionSettings(
    val maxSteps: Int = 1000,
    val maxLoopIterations: Int = 100,
    val nodeTimeoutMs: Long = 30_000,
    val totalTimeoutMs: Long = 300_000,
)

@Serializable
data class WorkflowDef(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val nodes: List<NodeDef> = emptyList(),
    val connections: List<ConnectionDef> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val settings: ExecutionSettings = ExecutionSettings(),
)

fun newId(): String = UUID.randomUUID().toString()

fun newNode(type: NodeType, x: Float, y: Float): NodeDef =
    NodeDef(newId(), type, type.title, x, y, NodeSpecs.defaultConfig(type))

fun newWorkflow(name: String): WorkflowDef = WorkflowDef(
    id = newId(),
    name = name.trim(),
    nodes = listOf(newNode(NodeType.START, 40f, 80f), newNode(NodeType.END, 560f, 80f)),
)

/** New ids for the workflow, its nodes and connections (node ids are global primary keys in Room). */
fun WorkflowDef.withFreshIds(): WorkflowDef {
    val map = nodes.associate { it.id to newId() }
    return copy(
        id = newId(),
        nodes = nodes.map { it.copy(id = map.getValue(it.id)) },
        connections = connections.mapNotNull { c ->
            val f = map[c.fromNode]
            val t = map[c.toNode]
            if (f == null || t == null) null else c.copy(id = newId(), fromNode = f, toNode = t)
        },
    )
}
