package dev.dshmobile.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadQueueTest {
    @Test fun `twenty files complete automatically with only twelve starts in any minute`() = runTest {
        val queue = UploadQueue { testScheduler.currentTime }
        val starts = mutableListOf<Pair<Int, Long>>()
        val pending = mutableSetOf<Int>()
        val jobs = (1..20).map { id -> launch {
            queue.upload({ if (it == -1) pending += id else pending -= id }) { starts += id to testScheduler.currentTime }
        } }
        runCurrent()
        assertEquals((1..12).toList(), starts.map { it.first })
        assertEquals((13..20).toSet(), pending)
        advanceTimeBy(59_999); runCurrent(); assertEquals(12, starts.size)
        advanceUntilIdle(); jobs.joinAll()
        assertEquals((1..20).toList(), starts.map { it.first })
        assertEquals(60_000L, starts[12].second)
        assertTrue(pending.isEmpty())
    }
    @Test fun `Retry After pauses the queue and retries without a user tap`() = runTest {
        val queue = UploadQueue { testScheduler.currentTime }
        val starts = mutableListOf<Long>()
        var attempts = 0
        launch { queue.upload({}) {
            starts += testScheduler.currentTime
            if (attempts++ == 0) throw MobileFailure("rate_limited", retryAfterMillis("90"))
        } }
        launch { queue.upload({}) { starts += testScheduler.currentTime } }
        runCurrent(); assertEquals(listOf(0L), starts)
        advanceTimeBy(89_999); runCurrent(); assertEquals(1, starts.size)
        advanceUntilIdle(); assertEquals(listOf(0L, 90_000L, 90_000L), starts)
    }
    @Test fun `removed queued files never upload and cancellation leaves queue usable`() = runTest {
        val queue = UploadQueue { testScheduler.currentTime }
        val starts = mutableListOf<Int>()
        repeat(12) { queue.upload({}) {} }
        val removed = launch { queue.upload({}) { starts += 13 } }
        val retained = launch { queue.upload({}) { starts += 14 } }
        runCurrent(); removed.cancelAndJoin(); advanceUntilIdle(); retained.join()
        assertEquals(listOf(14), starts)
    }
    @Test fun `non rate errors are not automatically replayed`() = runTest {
        val queue = UploadQueue { testScheduler.currentTime }
        var calls = 0
        try { queue.upload({}) { calls++; throw MobileFailure("network_unavailable") }; fail() }
        catch (failure: MobileFailure) { assertEquals("network_unavailable", failure.key) }
        assertEquals(1, calls)
    }
}
