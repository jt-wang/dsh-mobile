package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable internal data class HistoryEntry(val snapshot: SessionSnapshot, val observedAt: Long)
@Serializable internal data class HistoryIndex(val workspaces: List<Workspace>, val sessions: List<SessionSummary>,
    val truncated: Boolean, val observedAt: Long)
@Serializable internal data class HistoryRecord(val binding: String? = null, val entries: List<HistoryEntry> = emptyList(),
    val index: HistoryIndex? = null)
internal interface HistoryStore {
    val authority: HistoryAuthority
    suspend fun read(): HistoryRecord
    suspend fun write(value: HistoryRecord)
    suspend fun clear()
}
internal class MemoryHistoryStore : HistoryStore {
    override val authority = HistoryAuthority()
    private var value = HistoryRecord()
    override suspend fun read() = value
    override suspend fun write(value: HistoryRecord) { this.value = value }
    override suspend fun clear() { value = HistoryRecord() }
}

/** Presentation only, bounded serialized UTF-8, never authorization or command reconciliation. */
internal class PersistentHistory(private val store: HistoryStore, private val now: () -> Long,
    private val maxEntries: Int = 8, private val maxBytes: Int = 3500 * 1024,
    private val maxAgeMs: Long = 24 * 60 * 60 * 1000L,
    private val flushScope: kotlinx.coroutines.CoroutineScope? = null) {
    @Volatile private var epoch = store.authority.epoch
    @Volatile private var dirty = false
    private var flushJob: kotlinx.coroutines.Job? = null
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private fun valid() = epoch == store.authority.epoch
    fun invalidateMemory() { flushJob?.cancel(); flushJob = null; dirty = false; record = HistoryRecord() }
    fun bindAuthorized(binding: String, expected: Long) {
        if (expected != store.authority.epoch) throw MobileFailure("revoked")
        if (!valid() || record.binding != binding) { invalidateMemory(); record = HistoryRecord(binding); epoch = expected }
    }
    private suspend fun changed() {
        revision.incrementAndGet(); dirty = true
        if (flushScope == null) { flush(); return }
        if (flushJob?.isActive != true) flushJob = flushScope.launch {
            while (dirty && valid()) {
                kotlinx.coroutines.delay(2000)
                try { flush() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { break } // Optional cache; a later snapshot may schedule another flush.
            }
        }
    }
    suspend fun flush() {
        store.authority.write(epoch) {
            if (dirty && valid()) {
                val before = revision.get(); val latest = record
                store.write(latest)
                if (revision.get() == before) dirty = false
            }
        }
    }
    @Volatile private var record = HistoryRecord()
    private fun fresh(at: Long) = now() - at in 0..maxAgeMs
    suspend fun restore(binding: String) {
        epoch = store.authority.epoch
        val loaded = try { store.read() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { HistoryRecord() }
        record = if (loaded.binding == binding) loaded else HistoryRecord(binding)
        if (loaded.binding != binding) store.clear()
        val entries = record.entries.takeLast(maxEntries).filter { fresh(it.observedAt) }
        record = record.copy(entries = emptyList(), index = record.index?.takeIf { fresh(it.observedAt) })
        for (entry in entries) {
            val checked = runCatching { entry.snapshot.checked(entry.snapshot.session.id) }.getOrNull() ?: continue
            admit(checked, entry.observedAt)
        }
    }
    fun get(id: String): SessionSnapshot? = if (!valid()) null else record.entries.find { it.snapshot.session.id == id && fresh(it.observedAt) }?.snapshot
    fun index(): HistoryIndex? = if (!valid()) null else record.index?.takeIf { fresh(it.observedAt) }
    suspend fun put(snapshot: SessionSnapshot) { if (!valid()) return; admit(snapshot, now()); changed() }
    private fun admit(snapshot: SessionSnapshot, at: Long) {
        var retained = record.entries.filterNot { it.snapshot.session.id == snapshot.session.id }.filter { fresh(it.observedAt) }
        val bytes = mobileJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8).size
        if (bytes <= 2 * 1024 * 1024 && bytes <= maxBytes) {
            retained = retained + HistoryEntry(snapshot, at)
            while (retained.size > maxEntries || retained.sumOf { mobileJson.encodeToString(it.snapshot).toByteArray(Charsets.UTF_8).size } > maxBytes)
                retained = retained.drop(1)
        }
        record = record.copy(entries = retained)
    }
    suspend fun updateIndex(index: HistoryIndex) {
        if (!valid()) return
        require(index.sessions.size <= 1000 && index.workspaces.size <= 100)
        var bounded = index
        while (mobileJson.encodeToString(bounded).toByteArray(Charsets.UTF_8).size > 1024 * 1024 && bounded.sessions.isNotEmpty())
            bounded = bounded.copy(sessions = bounded.sessions.dropLast(1), truncated = true)
        record = record.copy(index = bounded)
        changed()
    }
    suspend fun retain(ids: Set<String>) { if (!valid()) return; record = record.copy(entries = record.entries.filter { it.snapshot.session.id in ids }); changed() }
    suspend fun clear() { invalidateMemory(); store.clear() }
}

internal fun historyBinding(host: PairedHost): String {
    val identity = mobileJson.encodeToString(listOf(host.endpoint.baseUrl, host.endpoint.pinSha256.orEmpty(), host.deviceId))
    return java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}
