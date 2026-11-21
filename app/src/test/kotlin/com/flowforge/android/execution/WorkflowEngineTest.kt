package com.flowforge.android.execution

import com.flowforge.android.domain.interfaces.DatasetProvider
import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult
import com.flowforge.android.workflow.c
import com.flowforge.android.workflow.model.ExecutionSettings
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.n
import com.flowforge.android.workflow.wf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class WorkflowEngineTest {
    private class FakeHttp(val respond: (HttpRequestSpec) -> HttpResult) : HttpClientPort {
        val requests = CopyOnWriteArrayList<HttpRequestSpec>()
        override suspend fun execute(spec: HttpRequestSpec): HttpResult {
            requests.add(spec)
            return respond(spec)
        }
    }

    private fun res(status: Int, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        HttpResult(status, headers, body, false, 5, "https://a.test/")

    private val logs = CopyOnWriteArrayList<String>()
    private fun engine(http: HttpClientPort = FakeHttp { res(200) }) =
        WorkflowEngine(DefaultNodeHandler(http) { logs.add(it) })

    @Test fun linearWorkflowSetsVariables() = runBlocking {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("a", NodeType.SET_VARIABLE, "name" to "a", "value" to "1"),
                n("b", NodeType.SET_VARIABLE, "name" to "b", "value" to "x\${a}y"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "a"), c("a", "out", "b"), c("b", "out", "e")),
        )
        val ctx = ExecutionContext()
        val r = engine().execute(w, ctx)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals("x1y", ctx.variables["b"])
        assertEquals(NodeStatus.SUCCESS, r.nodeStates.getValue("b").status)
    }

    @Test fun conditionPicksTheRightBranch() = runBlocking {
        fun build(value: String) = wf(
            listOf(
                n("s", NodeType.START),
                n("v", NodeType.SET_VARIABLE, "name" to "a", "value" to value),
                n("cond", NodeType.CONDITION, "left" to "\${a}", "op" to "eq", "right" to "1"),
                n("yes", NodeType.SET_VARIABLE, "name" to "r", "value" to "yes"),
                n("no", NodeType.SET_VARIABLE, "name" to "r", "value" to "no"),
                n("e", NodeType.END),
            ),
            listOf(
                c("s", "out", "v"), c("v", "out", "cond"), c("cond", "true", "yes"), c("cond", "false", "no"),
                c("yes", "out", "e"), c("no", "out", "e"),
            ),
        )
        val c1 = ExecutionContext(); engine().execute(build("1"), c1)
        val c2 = ExecutionContext(); engine().execute(build("2"), c2)
        assertEquals("yes", c1.variables["r"])
        assertEquals("no", c2.variables["r"])
    }

    @Test fun httpThenJsonParserPassesVariablesBetweenNodes() = runBlocking {
        val http = FakeHttp { res(200, """{"token":"abc"}""") }
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/login", "method" to "POST", "bodyType" to "JSON", "body" to "{}"),
                n("p", NodeType.JSON_PARSER, "path" to "$.token", "saveAs" to "tok"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "h"), c("h", "success", "p"), c("p", "success", "e")),
        )
        val ctx = ExecutionContext()
        val r = engine(http).execute(w, ctx)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals("abc", ctx.variables["tok"])
        assertEquals("200", ctx.variables["resp.status"])
        assertEquals(200, r.nodeStates.getValue("h").httpStatus)
        assertEquals("POST", http.requests.single().method)
    }

    @Test fun httpErrorStatusWithoutErrorBranchFailsTheRun() = runBlocking {
        val w = wf(
            listOf(n("s", NodeType.START), n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/"), n("e", NodeType.END)),
            listOf(c("s", "out", "h"), c("h", "success", "e")),
        )
        val r = engine(FakeHttp { res(500, "boom") }).execute(w)
        assertEquals(RunStatus.FAILED, r.status)
        assertEquals(NodeStatus.FAILED, r.nodeStates.getValue("h").status)
        assertEquals(500, r.nodeStates.getValue("h").httpStatus)
    }

    @Test fun errorBranchContinuesButNodeStaysFailed() = runBlocking {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/"),
                n("eb", NodeType.ERROR_BRANCH),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "h"), c("h", "error", "eb"), c("eb", "match", "e")),
        )
        val r = engine(FakeHttp { throw HttpEngineException(HttpEngineException.Kind.NETWORK, "down") }).execute(w)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals(NodeStatus.FAILED, r.nodeStates.getValue("h").status)
    }

    @Test fun parserFailureIsNotSuccess() = runBlocking {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/"),
                n("p", NodeType.JSON_PARSER, "path" to "$.missing"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "h"), c("h", "success", "p"), c("p", "success", "e")),
        )
        val r = engine(FakeHttp { res(200, "{}") }).execute(w)
        assertEquals(RunStatus.FAILED, r.status)
        assertEquals(NodeStatus.FAILED, r.nodeStates.getValue("p").status)
    }

    @Test fun unresolvedVariableFailsTheNode() = runBlocking {
        val w = wf(
            listOf(n("s", NodeType.START), n("a", NodeType.SET_VARIABLE, "name" to "x", "value" to "\${nope}"), n("e", NodeType.END)),
            listOf(c("s", "out", "a"), c("a", "out", "e")),
        )
        val r = engine().execute(w)
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.error!!.contains("nope"))
    }

    @Test fun loopRunsTheBodyTheConfiguredNumberOfTimes() = runBlocking {
        logs.clear()
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("l", NodeType.LOOP, "count" to "3"),
                n("log", NodeType.LOGGER, "message" to "i=\${loop.index}"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "l"), c("l", "body", "log"), c("log", "out", "l"), c("l", "done", "e")),
        )
        val r = engine().execute(w)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals(listOf("i=0", "i=1", "i=2"), logs.toList())
    }

    @Test fun stepLimitStopsRunawayLoops() = runBlocking {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("l", NodeType.LOOP, "count" to "100"),
                n("log", NodeType.LOGGER, "message" to "x"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "l"), c("l", "body", "log"), c("log", "out", "l"), c("l", "done", "e")),
            settings = ExecutionSettings(maxSteps = 20, maxLoopIterations = 100),
        )
        val r = engine().execute(w)
        assertEquals(RunStatus.LIMIT_EXCEEDED, r.status)
        assertTrue(r.steps <= 20)
    }

    @Test fun loopCountAboveTheCapIsRejected() = runBlocking {
        val w = wf(
            listOf(n("s", NodeType.START), n("l", NodeType.LOOP, "count" to "101"), n("e", NodeType.END)),
            listOf(c("s", "out", "l"), c("l", "done", "e"), c("l", "body", "e")),
        )
        assertEquals(RunStatus.FAILED, engine().execute(w).status)
    }

    @Test fun invalidGraphIsNeverExecuted() = runBlocking {
        val http = FakeHttp { res(200) }
        val w = wf(listOf(n("s", NodeType.START), n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/")), listOf(c("s", "out", "h")))
        val r = engine(http).execute(w)
        assertEquals(RunStatus.INVALID, r.status)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun nodeTimeoutFailsTheNode() = runBlocking {
        val w = wf(
            listOf(n("s", NodeType.START), n("d", NodeType.DELAY, "ms" to "3000"), n("e", NodeType.END)),
            listOf(c("s", "out", "d"), c("d", "out", "e")),
            settings = ExecutionSettings(nodeTimeoutMs = 100),
        )
        val r = engine().execute(w)
        assertEquals(RunStatus.FAILED, r.status)
        assertTrue(r.nodeStates.getValue("d").error!!.contains("timed out"))
    }

    @Test fun cancellationStopsTheRunAndMarksTheNode() = runBlocking {
        val w = wf(
            listOf(n("s", NodeType.START), n("d", NodeType.DELAY, "ms" to "30000"), n("e", NodeType.END)),
            listOf(c("s", "out", "d"), c("d", "out", "e")),
            settings = ExecutionSettings(nodeTimeoutMs = 60_000),
        )
        val events = CopyOnWriteArrayList<ExecutionEvent>()
        val job = launch { engine().execute(w, onEvent = { events.add(it) }) }
        delay(200)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(events.any { it.nodeId == "d" && it.state.status == NodeStatus.CANCELLED })
    }

    @Test fun datasetRowsDriveALoop() = runBlocking {
        logs.clear()
        val rows = ArrayDeque(listOf("a", "b"))
        val provider = object : DatasetProvider {
            override suspend fun nextRow(datasetName: String): String? = rows.removeFirstOrNull()
        }
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("d", NodeType.INPUT_DATASET, "name" to "ds"),
                n("log", NodeType.LOGGER, "message" to "row=\${row}"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "d"), c("d", "row", "log"), c("log", "out", "d"), c("d", "end", "e")),
        )
        val r = engine().execute(w, ExecutionContext(datasets = provider))
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals(listOf("row=a", "row=b"), logs.toList())
    }

    @Test fun loggerRedactsSecrets() = runBlocking {
        logs.clear()
        val w = wf(
            listOf(n("s", NodeType.START), n("l", NodeType.LOGGER, "message" to "password=hunter2"), n("e", NodeType.END)),
            listOf(c("s", "out", "l"), c("l", "out", "e")),
        )
        engine().execute(w)
        assertEquals(listOf("password=***"), logs.toList())
    }

    @Test fun dataValidationInvalidFollowsInvalidPort() = runBlocking {
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("v", NodeType.SET_VARIABLE, "name" to "x", "value" to "abc"),
                n("dv", NodeType.DATA_VALIDATION, "source" to "x", "rule" to "number"),
                n("ok", NodeType.SET_VARIABLE, "name" to "r", "value" to "valid"),
                n("bad", NodeType.SET_VARIABLE, "name" to "r", "value" to "invalid"),
                n("e", NodeType.END),
            ),
            listOf(
                c("s", "out", "v"), c("v", "out", "dv"), c("dv", "valid", "ok"), c("dv", "invalid", "bad"),
                c("ok", "out", "e"), c("bad", "out", "e"),
            ),
        )
        val ctx = ExecutionContext()
        engine().execute(w, ctx)
        assertEquals("invalid", ctx.variables["r"])
    }
}
