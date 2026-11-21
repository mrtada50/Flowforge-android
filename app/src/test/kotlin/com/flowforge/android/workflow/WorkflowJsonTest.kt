package com.flowforge.android.workflow

import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.model.newWorkflow
import com.flowforge.android.workflow.model.withFreshIds
import com.flowforge.android.workflow.serialization.WorkflowJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowJsonTest {
    private val sample = wf(
        listOf(n("s", NodeType.START), n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/x"), n("e", NodeType.END)),
        listOf(c("s", "out", "h"), c("h", "success", "e")),
        vars = mapOf("k" to "v"),
    )

    @Test fun roundTrip() {
        val text = WorkflowJson.encode(sample)
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertEquals(sample, WorkflowJson.decode(text).getOrThrow())
    }

    @Test fun rejectsNewerSchema() {
        val text = WorkflowJson.encode(sample).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        assertTrue(WorkflowJson.decode(text).isFailure)
    }

    @Test fun rejectsMissingSchemaVersion() {
        assertTrue(WorkflowJson.decode("""{"id":"a","name":"b"}""").isFailure)
    }

    @Test fun rejectsInvalidJson() = assertTrue(WorkflowJson.decode("{not json").isFailure)

    @Test fun rejectsUnknownNodeType() {
        val text = """{"schemaVersion":1,"id":"a","name":"b","nodes":[{"id":"n","type":"HACK","name":"x"}]}"""
        assertTrue(WorkflowJson.decode(text).isFailure)
    }

    @Test fun rejectsMissingId() = assertTrue(WorkflowJson.decode("""{"schemaVersion":1,"name":"b"}""").isFailure)

    @Test fun freshIdsKeepStructure() {
        val copy = sample.withFreshIds()
        assertNotEquals(sample.id, copy.id)
        assertEquals(sample.nodes.size, copy.nodes.size)
        assertEquals(sample.connections.size, copy.connections.size)
        val ids = copy.nodes.map { it.id }.toSet()
        assertTrue(copy.connections.all { it.fromNode in ids && it.toNode in ids })
        assertTrue(ids.intersect(sample.nodes.map { it.id }.toSet()).isEmpty())
    }

    @Test fun newWorkflowHasStartAndEnd() {
        val w = newWorkflow("x")
        assertEquals(setOf(NodeType.START, NodeType.END), w.nodes.map { it.type }.toSet())
    }
}
