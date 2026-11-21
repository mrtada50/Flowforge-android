package com.flowforge.android.workflow

import com.flowforge.android.workflow.model.NodeSpecs
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.validation.Severity
import com.flowforge.android.workflow.validation.WorkflowValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidatorTest {
    private fun codes(w: com.flowforge.android.workflow.model.WorkflowDef, sev: Severity = Severity.ERROR) =
        WorkflowValidator.validate(w).filter { it.severity == sev }.map { it.code }

    private val simple = wf(
        listOf(n("s", NodeType.START), n("e", NodeType.END)),
        listOf(c("s", "out", "e")),
    )

    @Test fun validSimpleWorkflow() = assertTrue(WorkflowValidator.isValid(WorkflowValidator.validate(simple)))

    @Test fun missingStartIsError() {
        assertTrue("START_COUNT" in codes(wf(listOf(n("e", NodeType.END)), emptyList())))
    }

    @Test fun twoStartsIsError() {
        val w = wf(listOf(n("s1", NodeType.START), n("s2", NodeType.START), n("e", NodeType.END)), emptyList())
        assertTrue("START_COUNT" in codes(w))
    }

    @Test fun missingEndIsError() {
        assertTrue("NO_END" in codes(wf(listOf(n("s", NodeType.START)), emptyList())))
    }

    @Test fun danglingConnectionIsError() {
        val w = wf(listOf(n("s", NodeType.START), n("e", NodeType.END)), listOf(c("s", "out", "ghost")))
        assertTrue("DANGLING_CONNECTION" in codes(w))
    }

    @Test fun unknownPortIsError() {
        val w = wf(listOf(n("s", NodeType.START), n("e", NodeType.END)), listOf(c("s", "nope", "e")))
        assertTrue("BAD_OUTPUT_PORT" in codes(w))
    }

    @Test fun twoConnectionsOnOneOutputIsError() {
        val w = wf(
            listOf(n("s", NodeType.START), n("e1", NodeType.END), n("e2", NodeType.END)),
            listOf(c("s", "out", "e1"), c("s", "out", "e2")),
        )
        assertTrue("MULTIPLE_OUTPUT_CONNECTIONS" in codes(w))
    }

    @Test fun missingRequiredFieldIsError() {
        val w = wf(
            listOf(n("s", NodeType.START), n("h", NodeType.HTTP_REQUEST), n("e", NodeType.END)),
            listOf(c("s", "out", "h"), c("h", "success", "e")),
        )
        assertTrue("MISSING_FIELD" in codes(w))
    }

    @Test fun cycleWithoutLoopIsError() {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("a", NodeType.LOGGER),
                n("b", NodeType.LOGGER),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "a"), c("a", "out", "b"), c("b", "out", "a")),
        )
        assertTrue("CYCLE" in codes(w))
    }

    @Test fun cycleThroughLoopBodyIsAllowed() {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("l", NodeType.LOOP),
                n("a", NodeType.LOGGER),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "l"), c("l", "body", "a"), c("a", "out", "l"), c("l", "done", "e")),
        )
        assertFalse("CYCLE" in codes(w))
        assertTrue(WorkflowValidator.isValid(WorkflowValidator.validate(w)))
    }

    @Test fun unreachableNodeIsWarning() {
        val w = wf(listOf(n("s", NodeType.START), n("e", NodeType.END), n("x", NodeType.LOGGER)), listOf(c("s", "out", "e")))
        assertTrue("UNREACHABLE" in codes(w, Severity.WARNING))
    }

    @Test fun canConnectRejectsIncompatibleConnections() {
        assertNotNull(WorkflowValidator.canConnect(simple, "e", "out", "s", "in")) // END has no output
        assertNotNull(WorkflowValidator.canConnect(simple, "s", "out", "s", "in")) // self
        assertNotNull(WorkflowValidator.canConnect(simple, "s", "out", "e", "in")) // already connected
    }

    @Test fun canConnectRejectsCycleOutsideLoop() {
        val w = wf(
            listOf(n("s", NodeType.START), n("a", NodeType.LOGGER), n("b", NodeType.LOGGER), n("e", NodeType.END)),
            listOf(c("s", "out", "a"), c("a", "out", "b")),
        )
        assertNotNull(WorkflowValidator.canConnect(w, "b", "out", "a", "in"))
        assertNull(WorkflowValidator.canConnect(w, "b", "out", "e", "in"))
    }

    @Test fun everyNodeTypeHasASpec() {
        NodeType.entries.forEach { NodeSpecs.of(it) }
        assertTrue(NodeSpecs.of(NodeType.START).inputs.isEmpty())
        assertTrue(NodeSpecs.of(NodeType.END).outputs.isEmpty())
        assertEquals(listOf("true", "false"), NodeSpecs.of(NodeType.CONDITION).outputs)
    }
}
