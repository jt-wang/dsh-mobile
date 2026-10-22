package dev.dshmobile.app.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One device lane for initial uploads and source refreshes. Never retries a prompt.
 * Progress -1 means queued (also during Retry-After); cancellation removes the waiter.
 */
internal class UploadQueue(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lane = Mutex()
    private val starts = ArrayDeque<Long>()
    private var retryAt = 0L

    suspend fun <T> upload(progress: (Int) -> Unit, send: suspend () -> T): T {
        progress(-1)
        return lane.withLock {
            while (true) {
                currentCoroutineContext().ensureActive()
                val instant = now()
                while (starts.isNotEmpty() && instant - starts.first() >= 60_000) starts.removeFirst()
                val next = maxOf(retryAt, if (starts.size >= 12) starts.first() + 60_000 else instant)
                if (next > instant) { progress(-1); delay(next - instant); continue }
                starts.addLast(instant)
                progress(0)
                try { return@withLock send() }
                catch (failure: MobileFailure) {
                    if (failure.key != "rate_limited") throw failure
                    // Missing/zero headers must not create a hot retry loop.
                    retryAt = now() + failure.retryAfterMs.coerceAtLeast(1000)
                    progress(-1)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
}
