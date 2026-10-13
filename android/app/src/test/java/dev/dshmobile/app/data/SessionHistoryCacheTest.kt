package dev.dshmobile.app.data

import dev.dshmobile.app.model.ChatMessage
import dev.dshmobile.app.model.SessionSnapshot
import dev.dshmobile.app.model.SessionSummary
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SessionHistoryCacheTest {
    private fun snapshot(id: String, text: String = "Synthetic") = SessionSnapshot(
        SessionSummary(id, "Synthetic chat", "workspace", 1, false, true),
        listOf(ChatMessage("answer-$id", "assistant", text, 1)), 1, false, "idle")

    @Test fun `entry and serialized byte budgets evict least recently viewed history`() {
        val first = snapshot("a")
        val second = snapshot("b")
        val third = snapshot("c")
        val size = mobileJson.encodeToString(first).toByteArray(Charsets.UTF_8).size
        for (cache in listOf(SessionHistoryCache({ 0L }, maxEntries = 2),
            SessionHistoryCache({ 0L }, maxEntries = 8, maxBytes = size * 2))) {
            cache.put(first); cache.put(second)
            assertSame(first, cache.get("a"))
            cache.put(third)
            assertNull(cache.get("b"))
            assertSame(first, cache.get("a"))
            assertSame(third, cache.get("c"))
        }
    }
    @Test fun `oversize replacement removes the old cut without emptying unrelated history`() {
        val first = snapshot("a")
        val bytes = mobileJson.encodeToString(first).toByteArray(Charsets.UTF_8).size
        val cache = SessionHistoryCache({ 0L }, maxBytes = bytes * 2)
        cache.put(first); cache.put(snapshot("b")); cache.put(snapshot("a", "x".repeat(bytes * 3)))
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
    }
    @Test fun `age uses last authoritative observation not reads and clock rollback invalidates history`() {
        var clock = 1_000L
        val cache = SessionHistoryCache({ clock }, maxAgeMs = 100)
        val first = snapshot("a")
        cache.put(first)
        clock += 90
        assertNotNull(cache.get("a"))
        clock += 11
        assertNull(cache.get("a"))
        cache.put(first)
        clock--
        assertNull(cache.get("a"))
        cache.put(first)
        clock += 90
        cache.put(first.copy()) // Identical authoritative replacement revalidates.
        clock += 90
        assertNotNull(cache.get("a"))
    }
    @Test fun `membership pruning and forget clear all retained text`() {
        val cache = SessionHistoryCache({ 0L })
        cache.put(snapshot("a")); cache.put(snapshot("b"))
        cache.retain(setOf("b"))
        assertNull(cache.get("a")); assertNotNull(cache.get("b"))
        cache.clear()
        assertNull(cache.get("b"))
    }
}
