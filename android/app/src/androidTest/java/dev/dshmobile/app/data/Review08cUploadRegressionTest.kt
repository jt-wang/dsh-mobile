package dev.dshmobile.app.data

import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.chat.PhoneEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

class Review08cUploadRegressionTest {
    private class Store(var value: StoredState): SecureStateStore {
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    private class Fixture: Dispatcher() {
        @Volatile var uploads = 0
        @Volatile var firstMissing = true
        @Volatile var uncertain = false
        @Volatile var holdUpload = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val messages = CopyOnWriteArrayList<String>()
        val deletes = CopyOnWriteArrayList<String>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
            fun json(body: String, status:Int=200) = MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
            if(request.method == "DELETE") { deletes.add(request.path!!); return MockResponse().setResponseCode(204) }
            return when(request.requestUrl!!.encodedPath) {
                "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":true,"questions":false,"approvals":false,"push":false}}""")
                "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                "/v1/presets" -> json("""{"items":[]}""")
                "/v1/sessions" -> json("""{"items":[$session],"nextCursor":null}""")
                "/v1/sessions/chat" -> json("""{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}""")
                "/v1/sessions/chat/messages" -> {
                    val body=request.body.readUtf8(); messages.add(body)
                    if(firstMissing && messages.size==1) json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""",404)
                    else if(uncertain) json("broken",200)
                    else json("""{"requestId":"${JSONObject(body).getString("requestId")}","status":"accepted","updatedAt":20}""")
                }
                "/v1/sessions/chat/uploads" -> {
                    uploads++
                    if(holdUpload && uploads==1) { entered.countDown(); check(release.await(10,TimeUnit.SECONDS)) }
                    if(uploads==2) json("""{"error":{"code":"rate_limited","message":"Synthetic","retryable":true}}""",429)
                    else json("""{"uploadId":"fresh-$uploads","expiresAt":9999999999999,"attachment":{"id":"ref","name":"test.txt","size":4,"mime":"text/plain","kind":"file"}}""",201)
                }
                else -> json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""",404)
            }
        }
    }
    private suspend fun exercise(block: suspend (NetworkMobileRepository, Store, Fixture, CoroutineScope) -> Unit) {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val f=Fixture()
        MockWebServer().use { server ->
            server.dispatcher=f; server.start()
            val sources=listOf("one","two").map { DraftAttachment(it,"content://synthetic/$it","test.txt",4,"text/plain",it,Long.MAX_VALUE) }
            val store=Store(StoredState(host=PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")),"device","synthetic-token-for-review-00000000","Synthetic"),selectedSessionId="chat",attachmentDrafts=mapOf("chat" to sources)))
            val repo=NetworkMobileRepository(store,owner,true,retryDelay={60_000},openAttachment={"test".byteInputStream()})
            try { repo.restore(); repo.setForeground(true); assertEquals(ConnectionState.ONLINE,repo.state.value.connection); block(repo,store,f,owner) }
            finally { f.release.countDown(); repo.close(); owner.cancel() }
        }
    }
    @Test fun removeDuringRefreshKeepsSuccessfulNewUpload() = runBlocking {
        exercise { repo,store,f,owner ->
            f.holdUpload=true
            val editor=withContext(Dispatchers.Main) { PhoneEditor(InstrumentationRegistry.getInstrumentation().targetContext,repo,owner) }
            val collector=owner.launch { repo.state.collect { editor.restore(it) } }
            try {
                withContext(Dispatchers.Main) { editor.restore(repo.state.value) }
                val send=owner.launch { repo.sendMessage("",null,listOf("one","two")) }
                assertTrue(withContext(Dispatchers.IO) { f.entered.await(5,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { editor.remove("chat","two") }
                // Let remove() enter saveAttachments and suspend on the admission mutex.
                withContext(Dispatchers.Main) { yield() }
                f.release.countDown(); send.join(); delay(500)
                assertEquals(listOf("one"),store.value.attachmentDrafts["chat"]!!.map { it.localId })
                assertEquals("A queued editor save overwrote a successfully refreshed upload", "fresh-1",store.value.attachmentDrafts["chat"]!!.single().uploadId)
                assertEquals(listOf("one"),withContext(Dispatchers.Main) { editor.inSession("chat").map { it.attachment.localId } })
            } finally { collector.cancel() }
        }
    }
    @Test fun partialExplicitResendReusesSuccessAndNeverRewritesAdmittedIds() = runBlocking {
        exercise { repo,store,f,_ ->
            f.firstMissing=false
            repo.saveAttachments("chat",store.value.attachmentDrafts["chat"]!!.map { it.copy(expiresAt=1) })
            repo.sendMessage("",null,listOf("one","two"))
            val original=store.value.acceptedPrompts.single().requestId
            repo.state.value.localPromptActions!!.resend(original)
            assertEquals(2,f.uploads)
            assertEquals(listOf("one","two"),store.value.acceptedPrompts.single { it.requestId==original }.uploadIds)
            assertEquals("fresh-1",store.value.acceptedPrompts.single { it.requestId==original }.attachmentSources.first().uploadId)
            repo.refresh()
            repo.state.value.localPromptActions!!.resend(original)
            assertEquals("First successful source uploaded twice",3,f.uploads)
            assertEquals(listOf("one","two"),store.value.acceptedPrompts.single { it.requestId==original }.uploadIds)
            assertEquals(2,f.messages.size)
            assertNotEquals(JSONObject(f.messages[0]).getString("requestId"),JSONObject(f.messages[1]).getString("requestId"))
        }
    }
    @Test fun uncertainRefreshDoesNotAutomaticallyResendCommand() = runBlocking {
        exercise { repo,store,f,_ ->
            f.firstMissing=false; f.uncertain=true
            repo.sendMessage("",null,listOf("one","two"))
            val original=store.value.pending!!.requestId
            repeat(2) { repo.resolvePending(); repo.refresh() }
            assertEquals(1,f.messages.size)
            assertEquals(original,store.value.pending!!.requestId)
            assertEquals(0,f.uploads)
        }
    }
}
