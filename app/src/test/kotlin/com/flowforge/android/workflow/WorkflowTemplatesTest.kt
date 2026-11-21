package com.flowforge.android.workflow

import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult
import com.flowforge.android.execution.DefaultNodeHandler
import com.flowforge.android.execution.ExecutionContext
import com.flowforge.android.execution.RunStatus
import com.flowforge.android.execution.WorkflowEngine
import com.flowforge.android.workflow.model.WorkflowTemplate
import com.flowforge.android.workflow.model.WorkflowTemplates
import com.flowforge.android.workflow.validation.WorkflowValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowTemplatesTest {
    private fun http(body: String) = object : HttpClientPort {
        override suspend fun execute(spec: HttpRequestSpec) = HttpResult(200, emptyList(), body, false, 1, spec.url)
    }

    @Test fun everyTemplateIsAValidWorkflow() {
        WorkflowTemplate.entries.forEach { t ->
            val issues = WorkflowValidator.validate(WorkflowTemplates.build(t, "x"))
            assertTrue("$t: ${issues.map { it.message }}", WorkflowValidator.isValid(issues))
        }
    }

    @Test fun httpCheckRuns() = runBlocking {
        val r = WorkflowEngine(DefaultNodeHandler(http("hi"))).execute(WorkflowTemplates.build(WorkflowTemplate.HTTP_CHECK, "x"))
        assertEquals(RunStatus.COMPLETED, r.status)
    }

    @Test fun jsonExtractReadsTheValue() = runBlocking {
        val ctx = ExecutionContext()
        val r = WorkflowEngine(DefaultNodeHandler(http("""{"id":7}"""))).execute(WorkflowTemplates.build(WorkflowTemplate.JSON_EXTRACT, "x"), ctx)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals("7", ctx.variables["value"])
    }

    @Test fun datasetItemNeedsARow() = runBlocking {
        val w = WorkflowTemplates.build(WorkflowTemplate.DATASET_ITEM, "x")
        val engine = WorkflowEngine(DefaultNodeHandler(http("ok")))
        assertEquals(RunStatus.FAILED, engine.execute(w, ExecutionContext()).status)
        assertEquals(RunStatus.COMPLETED, engine.execute(w, ExecutionContext(mapOf("row" to "abc"))).status)
    }
}
