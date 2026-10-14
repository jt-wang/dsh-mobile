package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPrefetchTest {
    private fun chat(id: String, running: Boolean = false, updated: Long = 1) = SessionSummary(id, "Synthetic", "project", updated, running, true)
    @Test fun `prefetch prioritizes running then recent with two reads and cancellation drops late work`() = runTest {
        val entered = mutableListOf<String>()
        val published = mutableListOf<String>()
        val worker = HistoryPrefetch(this, read = { id ->
            entered += id
            delay(1000)
            SessionSnapshot(chat(id), emptyList(), -1, false, "idle")
        }, publish = { published += it.session.id })
        worker.start(listOf(chat("old"), chat("recent", updated = 10), chat("running", true)))
        runCurrent()
        assertEquals(listOf("running", "recent"), entered)
        worker.cancel(); advanceUntilIdle()
        assertTrue(published.isEmpty())
        assertEquals(2, entered.size)
    }
    @Test fun `later network failure never shortens Retry After pause`() = runTest {
        val entered = mutableListOf<String>()
        val worker = HistoryPrefetch(this, now = { testScheduler.currentTime }, read = { id ->
            entered += id
            if (id == "a") { delay(10); throw MobileFailure("rate_limited", 300000) }
            delay(20); throw MobileFailure("network_unavailable")
        }, publish = {})
        val chats = listOf(chat("a"), chat("b"))
        worker.start(chats); advanceUntilIdle()
        testScheduler.advanceTimeBy(61000); worker.start(chats); runCurrent()
        assertEquals(2, entered.size)
        testScheduler.advanceTimeBy(300000); worker.start(chats); advanceUntilIdle()
        assertEquals(4, entered.size)
    }
    @Test fun `rate limit stops remaining prefetch and cooldown prevents another batch`() = runTest {
        val entered = mutableListOf<String>()
        val worker = HistoryPrefetch(this, now = { testScheduler.currentTime }, read = { id ->
            entered += id
            throw MobileFailure("rate_limited")
        }, publish = { error("No result") })
        val chats = (1..8).map { chat("chat-$it") }
        worker.start(chats); advanceUntilIdle()
        assertTrue(entered.size <= 2)
        val count = entered.size
        worker.start(chats); advanceUntilIdle()
        assertEquals(count, entered.size)
        testScheduler.advanceTimeBy(60001)
        worker.start(chats); advanceUntilIdle()
        assertTrue(entered.size > count)
    }
}
