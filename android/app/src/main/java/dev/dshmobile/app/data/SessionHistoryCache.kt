package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import kotlinx.serialization.encodeToString

/** Memory-only, owner-scoped last-known surfaces. Never a source of authorization or receipts. */
internal class SessionHistoryCache(
    private val now: () -> Long,
    private val maxEntries: Int = 8,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxAgeMs: Long = 10 * 60 * 1_000L,
) {
    private data class Entry(val snapshot: SessionSnapshot, val observedAt: Long, val bytes: Int)
    private val entries = LinkedHashMap<String, Entry>()
    private var bytes = 0

    fun put(snapshot: SessionSnapshot) {
        val previous = entries[snapshot.session.id]
        if (previous?.snapshot == snapshot) {
            entries[snapshot.session.id] = previous.copy(observedAt = now())
            return
        }
        val size = mobileJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size
        remove(snapshot.session.id)
        if (size > maxBytes) return
        while (entries.size >= maxEntries || bytes + size > maxBytes) remove(entries.keys.first())
        entries[snapshot.session.id] = Entry(snapshot, now(), size)
        bytes += size
    }
    fun get(id: String): SessionSnapshot? {
        val entry = entries.remove(id) ?: return null
        val age = now() - entry.observedAt
        if (age !in 0..maxAgeMs) { bytes -= entry.bytes; return null }
        entries[id] = entry
        return entry.snapshot
    }
    fun retain(ids: Set<String>) { entries.keys.toList().filterNot { it in ids }.forEach(::remove) }
    fun clear() { entries.clear(); bytes = 0 }
    private fun remove(id: String) { entries.remove(id)?.let { bytes -= it.bytes } }
}
