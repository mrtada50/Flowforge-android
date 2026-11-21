package com.flowforge.android.execution

import com.flowforge.android.domain.interfaces.DatasetProvider
import com.flowforge.android.workflow.model.WorkflowDef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Source of dataset rows shared by all workers. */
interface RowSource {
    suspend fun next(): String?
    fun close() {}
}

class ListRowSource(rows: List<String>) : RowSource {
    private val queue = ArrayDeque(rows)
    private val mutex = Mutex()
    override suspend fun next(): String? = mutex.withLock { queue.removeFirstOrNull() }
}

/** Gives a workflow its one row: the first nextRow returns it, later calls return null. */
class SingleRowProvider(private val row: String) : DatasetProvider {
    private val used = AtomicBoolean(false)
    override suspend fun nextRow(datasetName: String): String? = if (used.compareAndSet(false, true)) row else null
}

class RunControl {
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    fun pause() { _paused.value = true }
    fun resume() { _paused.value = false }
    suspend fun awaitResumed() {
        if (_paused.value) _paused.first { !it }
    }
}

/** Spaces out starts so that at most [perSecond] items begin each second (across all workers). */
class RateLimiter(perSecond: Int) {
    private val intervalNs: Long = if (perSecond <= 0) 0L else 1_000_000_000L / perSecond
    private val mutex = Mutex()
    private var nextAt = 0L

    suspend fun acquire() {
        if (intervalNs == 0L) return
        val waitNs = mutex.withLock {
            val now = System.nanoTime()
            val start = maxOf(now, nextAt)
            nextAt = start + intervalNs
            start - now
        }
        if (waitNs > 0) delay(waitNs / 1_000_000)
    }
}

data class BatchConfig(
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val maxPerSecond: Int = DEFAULT_RATE,
    val maxItems: Int = DEFAULT_ITEMS,
    /** Optional column delimiter: row.0, row.1, ... are set when non-empty. */
    val delimiter: String = "",
    val skipHeader: Boolean = false,
) {
    /** Always enforces the hard limits. There is no "unlimited". */
    fun sanitized(): BatchConfig = copy(
        concurrency = concurrency.coerceIn(1, MAX_CONCURRENCY),
        maxPerSecond = maxPerSecond.coerceIn(1, MAX_RATE),
        maxItems = maxItems.coerceIn(1, MAX_ITEMS),
    )

    companion object {
        const val DEFAULT_CONCURRENCY = 2
        const val MAX_CONCURRENCY = 8
        const val DEFAULT_RATE = 5
        const val MAX_RATE = 20
        const val DEFAULT_ITEMS = 1000
        const val MAX_ITEMS = 100_000
    }
}

data class ItemResult(
    val index: Int,
    val status: RunStatus?,
    val error: String?,
    val httpStatus: Int?,
    val httpMs: Long,
    val threw: Boolean,
)

/** Runs one workflow per dataset row with bounded concurrency and a bounded start rate. */
class BatchRunner(private val engine: WorkflowEngine) {

    suspend fun run(
        wf: WorkflowDef,
        source: RowSource,
        config: BatchConfig,
        control: RunControl = RunControl(),
        onActiveWorkers: (Int) -> Unit = {},
        onItem: (ItemResult) -> Unit,
    ) {
        val cfg = config.sanitized()
        val limiter = RateLimiter(cfg.maxPerSecond)
        val claimed = AtomicInteger(0)
        val active = AtomicInteger(0)
        coroutineScope {
            repeat(cfg.concurrency) {
                launch {
                    while (true) {
                        control.awaitResumed()
                        ensureActive()
                        val index = claimed.incrementAndGet()
                        if (index > cfg.maxItems) break
                        val row = source.next() ?: break
                        limiter.acquire()
                        onActiveWorkers(active.incrementAndGet())
                        try {
                            onItem(runOne(wf, row, index, cfg))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onItem(ItemResult(index, null, e.message ?: e::class.java.simpleName, null, 0, true))
                        } finally {
                            onActiveWorkers(active.decrementAndGet())
                        }
                    }
                }
            }
        }
    }

    private suspend fun runOne(wf: WorkflowDef, row: String, index: Int, cfg: BatchConfig): ItemResult {
        val ctx = ExecutionContext(wf.variables, SingleRowProvider(row))
        ctx.variables["row"] = row
        if (cfg.delimiter.isNotEmpty()) {
            row.split(cfg.delimiter).forEachIndexed { i, v -> ctx.variables["row.$i"] = v }
        }
        val result = engine.execute(wf, ctx)
        val httpNodes = result.nodeStates.values.filter { it.httpStatus != null }
        return ItemResult(
            index = index,
            status = result.status,
            error = result.error,
            httpStatus = httpNodes.lastOrNull()?.httpStatus,
            httpMs = httpNodes.sumOf { it.durationMs },
            threw = false,
        )
    }
}
