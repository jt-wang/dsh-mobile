package dev.dshmobile.app.data

import dev.dshmobile.app.model.SessionSnapshot
import dev.dshmobile.app.model.SessionSummary
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** A read-only seam: no command API, at most five cold GETs and two in flight per batch. */
internal class HistoryPrefetch(private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val read: suspend (String) -> SessionSnapshot,
    private val publish: suspend (SessionSnapshot) -> Unit,
    private val terminal: suspend (String) -> Unit = {}) {
    private var job: Job? = null
    @Volatile private var pausedUntil = 0L
    @Synchronized fun cancel() { job?.cancel(); job = null }
    @Synchronized fun reset() { cancel(); pausedUntil = 0 }
    @Synchronized fun start(chats: List<SessionSummary>) {
        cancel()
        if (now() < pausedUntil) return
        val recent = chats.sortedByDescending { it.updatedAt }
        val targets = (recent.take(3) + recent.filter { it.running }.take(2)).distinctBy { it.id }.take(5)
            .sortedWith(compareByDescending<SessionSummary> { it.running }.thenByDescending { it.updatedAt })
        job = scope.launch {
            val next = AtomicInteger()
            coroutineScope {
                repeat(minOf(2, targets.size)) {
                    launch {
                        while (isActive && now() >= pausedUntil) {
                            val chat = targets.getOrNull(next.getAndIncrement()) ?: break
                            try {
                                val snapshot = read(chat.id)
                                ensureActive()
                                publish(snapshot)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: MobileFailure) {
                                if (failure.key in setOf("revoked", "unauthorized", "forbidden", "tls_failed", "certificate_invalid")) {
                                    synchronized(this@HistoryPrefetch) { pausedUntil = Long.MAX_VALUE }
                                    terminal(failure.key)
                                } else synchronized(this@HistoryPrefetch) {
                                    pausedUntil = maxOf(pausedUntil, now() + maxOf(failure.retryAfterMs, if (failure.key == "rate_limited") 60000L else 30000L))
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
