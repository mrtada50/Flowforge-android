package com.flowforge.android.workflow.serialization

import com.flowforge.android.workflow.model.CURRENT_SCHEMA_VERSION
import com.flowforge.android.workflow.model.WorkflowDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

object WorkflowJson {
    const val MAX_IMPORT_CHARS = 2_000_000

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Version N -> N+1 upgrade steps. Empty while schema 1 is the only version. */
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    fun encode(workflow: WorkflowDef): String = json.encodeToString(WorkflowDef.serializer(), workflow)

    fun decode(text: String): Result<WorkflowDef> = runCatching {
        require(text.length <= MAX_IMPORT_CHARS) { "File is too large" }
        val root = json.parseToJsonElement(text)
        require(root is JsonObject) { "A workflow file must be a JSON object" }
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw IllegalArgumentException("Missing schemaVersion")
        require(version in 1..CURRENT_SCHEMA_VERSION) {
            "Unsupported schema version $version (supported: 1..$CURRENT_SCHEMA_VERSION)"
        }
        var obj: JsonObject = root
        var v = version
        while (v < CURRENT_SCHEMA_VERSION) {
            val step = migrations[v] ?: throw IllegalStateException("No migration from schema $v")
            obj = step(obj)
            v++
        }
        json.decodeFromJsonElement(WorkflowDef.serializer(), obj)
    }
}
