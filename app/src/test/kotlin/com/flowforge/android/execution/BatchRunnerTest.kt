package com.flowforge.android.execution

import com.flowforge.android.domain.interfaces.HttpClientPort
import com.flowforge.android.domain.models.HttpEngineException
import com.flowforge.android.domain.models.HttpRequestSpec
import com.flowforge.android.domain.models.HttpResult
import com.flowforge.android.workflow.c
import com.flowforge.android.workflow.model.NodeType
import com.flowforge.android.workflow.n
import com.flowforge.android.workflow.wf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BatchRunnerTest {
    private class Probe : HttpClientPort {
        val current = AtomicInteger(0)
        val max = AtomicInteger(0)
        override suspend fun execute(spec: HttpRequestSpec): HttpResult {
            val now = current.incrementAndGet()
            max.updateAndGet { maxOf(it, now) }
            try {
                delay(30)
                if (spec.url.endsWith("/bad")) throw HttpEngineException(HttpEngineException.Kind.NETWORK, "down")
            } finally {
                current.decrementAndGet()
            }
            return HttpResult(200, emptyList(), "ok", false, 30, spec.url)
        }
    }

    private val flow = wf(
        listOf(n("s", NodeType.START), n("h", NodeType.HTTP_REQUEST, "url" to "https://a.test/\${row}"), n("e", NodeType.END)),
        listOf(c("s", "out", "h"), c("h", "success", "e")),
    )

    private fun rows(count: Int) = ListRowSource((0 until count).map { "r$it" })
    private fun runner(http: HttpClientPort) = BatchRunner(WorkflowEngine(DefaultNodeHandler(http)))

    @Test fun processesEveryRowWithinTheConcurrencyCap() = runBlocking {
        val probe = Probe()
        val results = CopyOnWriteArrayList<ItemResult>()
        runner(probe).run(flow, rows(20), BatchConfig(concurrency = 3, maxPerSecond = 20, maxItems = 100)) { results.add(it) }
        assertEquals(20, results.size)
        assertTrue(results.all { it.status == RunStatus.COMPLETED })
        assertTrue("max concurrent was ${probe.max.get()}", probe.max.get() <= 3)
        assertEquals(20, results.map { it.index }.toSet().size)
    }

    @Test fun hardLimitsAreAlwaysEnforced() {
        val c = BatchConfig(concurrency = 100, maxPerSecond = 1000, maxItems = 10_000_000).sanitized()
        assertEquals(BatchConfig.MAX_CONCURRENCY, c.concurrency)
        assertEquals(BatchConfig.MAX_RATE, c.maxPerSecond)
        assertEquals(BatchConfig.MAX_ITEMS, c.maxItems)
        val low = BatchConfig(concurrency = 0, maxPerSecond = 0, maxItems = 0).sanitized()
        assertEquals(1, low.concurrency)
        assertEquals(1, low.maxPerSecond)
        assertEquals(1, low.maxItems)
    }

    @Test fun maxItemsStopsTheRun() = runBlocking {
        val results = CopyOnWriteArrayList<ItemResult>()
        runner(Probe()).run(flow, rows(50), BatchConfig(concurrency = 2, maxPerSecond = 20, maxItems = 7)) { results.add(it) }
        assertEquals(7, results.size)
    }

    @Test fun aFailingItemDoesNotStopTheOthers() = runBlocking {
        val results = CopyOnWriteArrayList<ItemResult>()
        val source = ListRowSource(listOf("a", "bad", "c"))
        runner(Probe()).run(flow, source, BatchConfig(concurrency = 1, maxPerSecond = 20)) { results.add(it) }
        assertEquals(3, results.size)
        assertEquals(2, results.count { it.status == RunStatus.COMPLETED })
        assertEquals(1, results.count { it.status == RunStatus.FAILED })
    }

    @Test fun cancellationStopsAllWorkers() = runBlocking {
        val results = CopyOnWriteArrayList<ItemResult>()
        val job = launch { runner(Probe()).run(flow, rows(1000), BatchConfig(concurrency = 2, maxPerSecond = 20, maxItems = 1000)) { results.add(it) } }
        delay(300)
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue("processed ${results.size}", results.size < 1000)
    }

    @Test fun pauseHoldsWorkUntilResume() = runBlocking {
        val results = CopyOnWriteArrayList<ItemResult>()
        val control = RunControl()
        control.pause()
        val job = launch { runner(Probe()).run(flow, rows(3), BatchConfig(concurrency = 2, maxPerSecond = 20), control) { results.add(it) } }
        delay(300)
        assertEquals(0, results.size)
        control.resume()
        withTimeout(5000) { job.join() }
        assertEquals(3, results.size)
    }

    @Test fun delimiterSplitsTheRowIntoColumns() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        val w = wf(
            listOf(n("s", NodeType.START), n("l", NodeType.LOGGER, "message" to "\${row.1}-\${row}"), n("e", NodeType.END)),
            listOf(c("s", "out", "l"), c("l", "out", "e")),
        )
        val r = BatchRunner(WorkflowEngine(DefaultNodeHandler(Probe()) { logs.add(it) }))
        r.run(w, ListRowSource(listOf("a,b")), BatchConfig(concurrency = 1, maxPerSecond = 20, delimiter = ",")) {}
        assertEquals(listOf("b-a,b"), logs.toList())
    }

    @Test fun datasetNodeSeesTheRowOnce() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        val w = wf(
            listOf(
                n("s", NodeType.START),
                n("d", NodeType.INPUT_DATASET, "name" to "x"),
                n("l", NodeType.LOGGER, "message" to "got \${row}"),
                n("e", NodeType.END),
            ),
            listOf(c("s", "out", "d"), c("d", "row", "l"), c("l", "out", "d"), c("d", "end", "e")),
        )
        BatchRunner(WorkflowEngine(DefaultNodeHandler(Probe()) { logs.add(it) }))
            .run(w, ListRowSource(listOf("only")), BatchConfig(concurrency = 1, maxPerSecond = 20)) {}
        assertEquals(listOf("got only"), logs.toList())
    }
}
