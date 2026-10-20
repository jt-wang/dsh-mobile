package dev.dshmobile.app.acceptance

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Repository data-ready timings against a disposable loopback demo; not frame-render timings. */
class StartupTimingTest {
    @Test fun coldRestoreAndCachedOpen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = InstrumentationRegistry.getArguments().getString("perfPort")
        if (port != null) {
            require(port in setOf("18980", "18982"))
            val store = EncryptedStateStore(context)
            val stored = store.read()
            store.write(stored.copy(host = stored.host?.let { PairedHost(HostEndpoint("http://127.0.0.1:$port"), it.deviceId, it.deviceToken, it.hostName) }))
        }
        var repo: MobileRepository = createMobileRepository(context, scope)
        try {
            repo.setForeground(true)
            if (!repo.state.value.paired) {
                val invitation = File(context.cacheDir, "perf-invitation.json").readText()
                repo.pair(invitation, "Synthetic performance emulator")
            }
            repo.selectSession("demo-session")
            repo.setForeground(false); repo.close()
            repo = createMobileRepository(context, scope)
            val start = SystemClock.elapsedRealtime()
            var cache = false; var online = false; var fresh = false
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.state.collect { state ->
                    val ms = SystemClock.elapsedRealtime() - start
                    if (!cache && state.sessions.isNotEmpty()) { cache = true; Log.i("DSHPerfTest", "cache_list_ms=$ms") }
                    if (!online && state.connection == ConnectionState.ONLINE) { online = true; Log.i("DSHPerfTest", "online_ms=$ms") }
                    if (!fresh && state.lastSyncedAt != null) { fresh = true; Log.i("DSHPerfTest", "list_fresh_ms=$ms") }
                }
            }
            repo.setForeground(true)
            val opened = SystemClock.elapsedRealtime()
            var chatCache = false
            val chatWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                repo.state.collect { state ->
                    if (!chatCache && state.snapshot != null && state.snapshotStale) {
                        chatCache = true; Log.i("DSHPerfTest", "chat_cache_ms=${SystemClock.elapsedRealtime() - opened}")
                    }
                }
            }
            repo.selectSession("demo-session")
            Log.i("DSHPerfTest", "chat_fresh_ms=${SystemClock.elapsedRealtime() - opened}")
            assertFalse(repo.state.value.snapshotStale)
            assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            chatWatcher.cancelAndJoin(); watcher.cancelAndJoin()
        } finally { repo.close(); scope.cancel() }
    }
}
