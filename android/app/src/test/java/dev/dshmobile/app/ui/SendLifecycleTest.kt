package dev.dshmobile.app.ui

import androidx.lifecycle.ViewModelStore
import dev.dshmobile.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class SendLifecycleTest {
    private class Store : SecureStateStore {
        var value = StoredState()
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    @Test fun `clearing ViewModel immediately after send keeps one POST alive until accepted`() = runBlocking {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        val store = Store()
        val posts = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
        val snapshot = """{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}"""
        fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/v1/pairings" -> json("""{"deviceId":"synthetic","deviceToken":"synthetic-device-token","hostName":"Synthetic","protocolVersion":1}""").setResponseCode(201)
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":false,"liveSnapshots":false,"attachments":false,"questions":false,"approvals":false,"push":false}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[$session],"nextCursor":null}""")
                    "/v1/sessions/chat" -> json(snapshot)
                    "/v1/sessions/chat/messages" -> {
                        posts.incrementAndGet(); entered.complete(Unit)
                        release.await(5, TimeUnit.SECONDS)
                        val id = mobileJson.parseToJsonElement(request.body.readUtf8()) as kotlinx.serialization.json.JsonObject
                        json("""{"requestId":${id["requestId"]},"status":"accepted","updatedAt":2}""")
                    }
                    else -> json("{}").setResponseCode(404)
                }
            }
            server.start()
            lateinit var repo: MobileRepository
            val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { owner -> NetworkMobileRepository(store, owner, true).also { repo = it } }
            val models = ViewModelStore().apply { put("send", vm) }
            try {
                repo.setForeground(true)
                repo.pair("""{"version":1,"baseUrl":"${server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/")}","pairingToken":"synthetic-invitation-token"}""", "Synthetic")
                assertEquals("pair error=${repo.state.value.error}", dev.dshmobile.app.model.ConnectionState.ONLINE, repo.state.value.connection)
                repo.selectSession("chat")
                assertEquals("select error=${repo.state.value.error}", "chat", repo.state.value.snapshot?.session?.id)
                main.scheduler.runCurrent() // Deliver the selected editor session before typing.
                vm.updateDraft("Synthetic survives clear")
                assertEquals("editor admission busy=${repo.state.value.busy} stale=${repo.state.value.snapshotStale} selected=${repo.state.value.snapshot?.session?.id} blocked=${vm.interactionBlocked}", "Synthetic survives clear", vm.editorDraft)
                main.scheduler.runCurrent()
                withTimeout(2000) { repo.state.first { it.draft == "Synthetic survives clear" } }
                repo.setForeground(false) // A stale enabled button can race a connectivity/lifecycle change.
                vm.sendMessage()
                withTimeout(2000) { repo.state.first { it.sendNotSent } }
                assertEquals(0, posts.get())
                repo.setForeground(true)
                main.scheduler.runCurrent()
                vm.retryNotSent() // The visible retry action dispatches only on this explicit tap.
                assertEquals("chat", vm.sendingSessionId)
                withTimeout(2000) { entered.await() }
                models.clear()
                release.countDown()
                withTimeout(2000) { repo.state.first { it.pending == null && it.acceptedPrompts.isNotEmpty() } }
                assertEquals(1, posts.get())
                assertEquals("accepted", store.value.acceptedPrompts.single().status)
                assertEquals("", repo.state.value.draft)
            } finally { release.countDown(); models.clear(); repo.close(); Dispatchers.resetMain() }
        }
    }
}
