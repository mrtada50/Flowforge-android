package com.flowforge.android.execution

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test fun spacesOutSequentialStarts() = runBlocking {
        val l = RateLimiter(10)
        val t0 = System.nanoTime()
        repeat(6) { l.acquire() }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took ${ms}ms", ms >= 400)
    }

    @Test fun spacesOutParallelStartsToo() = runBlocking {
        val l = RateLimiter(10)
        val t0 = System.nanoTime()
        (1..6).map { async { l.acquire() } }.awaitAll()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took ${ms}ms", ms >= 400)
    }

    @Test fun zeroRateMeansNoWaiting() = runBlocking {
        val l = RateLimiter(0)
        val t0 = System.nanoTime()
        repeat(50) { l.acquire() }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
    }
}
