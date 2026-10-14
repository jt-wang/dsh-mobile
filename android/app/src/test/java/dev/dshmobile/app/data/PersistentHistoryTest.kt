package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersistentHistoryTest {
    private class Disk : HistoryStore {
        override val authority = HistoryAuthority()
        var value = HistoryRecord()
        override suspend fun read() = value
        override suspend fun write(value: HistoryRecord) { this.value = value }
        override suspend fun clear() { value = HistoryRecord() }
    }
    private val snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic", "project", 1, false, true),
        listOf(ChatMessage("old", "assistant", "Synthetic answer", 1)), 90, false, "idle")
    @Test fun `process recreation restores bounded history only for exact host binding and clear erases it`() = runBlocking {
        val disk = Disk()
        val first = PersistentHistory(disk, { 100L })
        first.restore("host-a")
        first.put(snapshot)
        val recreated = PersistentHistory(disk, { 101L })
        recreated.restore("host-a")
        assertEquals(snapshot, recreated.get("chat"))
        val other = PersistentHistory(disk, { 101L })
        other.restore("host-b")
        assertNull(other.get("chat"))
        assertTrue(disk.value.entries.isEmpty())
        other.put(snapshot); other.clear()
        recreated.restore("host-b")
        assertNull(recreated.get("chat"))
    }
    @Test fun `encrypted disk boundary survives new store owners without plaintext and clear deletes key`() = runBlocking {
        var bytes: ByteArray? = null
        var keyDeleted = false
        val file = object : AtomicStateFile {
            override val identity = "synthetic-history-test"
            override fun openRead(): java.io.InputStream = java.io.ByteArrayInputStream(bytes ?: throw java.io.FileNotFoundException())
            override fun startWrite(): java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun finishWrite(stream: java.io.OutputStream) { bytes = (stream as java.io.ByteArrayOutputStream).toByteArray() }
            override fun failWrite(stream: java.io.OutputStream?) = Unit
            override fun delete() { bytes = null }
        }
        val cipher = StateCipher { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        val disk = EncryptedHistoryStore(file, cipher) { keyDeleted = true }
        val cache = PersistentHistory(disk, { 100L })
        cache.restore("host"); cache.put(snapshot)
        assertFalse(bytes!!.toString(Charsets.UTF_8).contains("Synthetic answer"))
        assertEquals(snapshot, EncryptedHistoryStore(file, cipher) {}.read().entries.single().snapshot)
        cache.clear(); assertNull(bytes); assertTrue(keyDeleted)
    }
    @Test fun `authoritative empty and rollback replace disk surface and expiry never revalidates on read`() = runBlocking {
        val disk = Disk()
        var clock = 100L
        val cache = PersistentHistory(disk, { clock }, maxAgeMs = 100, maxEntries = 2)
        cache.restore("host")
        cache.put(snapshot)
        cache.put(snapshot.copy(messages = emptyList(), cursor = -1))
        val restored = PersistentHistory(disk, { clock }, maxAgeMs = 100)
        restored.restore("host")
        assertEquals(-1L, restored.get("chat")?.cursor)
        assertTrue(restored.get("chat")!!.messages.isEmpty())
        clock = 201L
        assertNull(restored.get("chat"))
        cache.put(snapshot)
        cache.put(snapshot.copy(session = snapshot.session.copy(id = "b")))
        cache.put(snapshot.copy(session = snapshot.session.copy(id = "c")))
        assertNull(cache.get("chat"))
        assertEquals(2, disk.value.entries.size)
    }
}
