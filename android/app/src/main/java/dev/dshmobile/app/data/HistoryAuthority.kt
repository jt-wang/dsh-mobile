package dev.dshmobile.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Shared physical-file owner: rejection publishes before deletion and serializes against every write. */
internal class HistoryAuthority {
    val mutex = Mutex()
    @Volatile var epoch = 0L
        private set
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    fun listen(listener: (String) -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    suspend fun invalidate(key: String, delete: suspend () -> Unit) = mutex.withLock {
        epoch++
        listeners.forEach { it(key) }
        delete()
    }
    suspend fun write(expected: Long, block: suspend () -> Unit) = mutex.withLock {
        if (expected == epoch) block()
    }
}
internal object HistoryAuthorities {
    private val owners = ConcurrentHashMap<String, HistoryAuthority>()
    fun owner(identity: String) = owners.computeIfAbsent(identity) { HistoryAuthority() }
}
