package dev.dshmobile.app.data

import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.chat.PhoneEditor
import dev.dshmobile.app.ui.chat.TemporaryMedia
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Regression cases adapted from the independent review probes. */
class PhoneRestoreRegressionTest {
    private class Store(var value: StoredState) : SecureStateStore {
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val attachment = DraftAttachment("local", "content://synthetic/source", "test.txt", 4, "text/plain", "upload", Long.MAX_VALUE)
    private fun host(url: String) = PairedHost(HostEndpoint(url), "synthetic-review-device", "synthetic-review-token-000000000000000", "Review")

    @Test fun lateConfirmationRemovesEditorAttachments() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repo = NetworkMobileRepository(Store(StoredState()), owner, true)
        try { withContext(Dispatchers.Main) {
            val editor = PhoneEditor(context, repo, owner)
            editor.restore(MobileState(paired = true, draftsRestored = true, attachmentDrafts = mapOf("chat" to listOf(attachment))))
            editor.restore(MobileState(paired = true, draftsRestored = true))
            assertTrue(editor.inSession("chat").isEmpty())
        } } finally { repo.close(); owner.cancel() }
    }
    @Test fun partialRestoreNeverDeletesPersistedCameraPhoto() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repo = NetworkMobileRepository(Store(StoredState()), owner, true)
        val file = java.io.File(TemporaryMedia.directory(context), "camera-review-restore.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", file)
        try { withContext(Dispatchers.Main) {
            val editor = PhoneEditor(context, repo, owner)
            editor.restore(MobileState(paired = true))
            yield()
            editor.restore(MobileState(paired = true, draftsRestored = true, attachmentDrafts = mapOf("chat" to listOf(attachment.copy(uri = uri.toString(), temporary = true)))))
            withContext(Dispatchers.IO) { delay(100) }
            assertTrue(file.exists())
            assertEquals(1, editor.inSession("chat").size)
        } } finally { repo.close(); owner.cancel(); file.delete() }
    }
    @Test fun invalidDraftIsDroppedWithoutLosingHostOrOtherDrafts() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val good = QuestionAnswer(listOf(AnswerItem("q", emptyList(), "valid")))
        val store = Store(StoredState(host = host("http://127.0.0.1:59998"), drafts = mapOf("chat" to "keep"), answerDrafts = mapOf(questionDraftKey("chat", "bad") to QuestionAnswer(listOf(AnswerItem("q", emptyList(), "я".repeat(32769)))), questionDraftKey("chat", "good") to good), attachmentDrafts = mapOf("bad" to listOf(attachment.copy(size = -1)), "good" to listOf(attachment))))
        val repo = NetworkMobileRepository(store, owner, true)
        try {
            repo.restore()
            assertNull(repo.state.value.error)
            assertTrue(repo.state.value.paired)
            assertEquals(mapOf(questionDraftKey("chat", "good") to good), store.value.answerDrafts)
            assertEquals(setOf("good"), store.value.attachmentDrafts.keys)
            repo.saveAnswerDraft("chat", "oversized", QuestionAnswer(listOf(AnswerItem("q", emptyList(), "x".repeat(32769)))))
            assertFalse(store.value.answerDrafts.containsKey(questionDraftKey("chat", "oversized")))
        } finally { repo.close(); owner.cancel() }
    }
    @Test fun canonicalRecoveryRemovesOnlyAttachmentsOfConfirmedRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val id = UUID.randomUUID().toString()
        val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl!!.encodedPath) {
                        "/v1/capabilities" -> """{"protocolVersion":1,"hostName":"Review","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":true,"questions":true,"approvals":false,"push":false}}"""
                        "/v1/workspaces" -> """{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}"""
                        "/v1/presets" -> """{"items":[]}"""
                        "/v1/sessions" -> """{"items":[$session],"nextCursor":null}"""
                        "/v1/sessions/chat" -> """{"session":$session,"messages":[{"id":"m","role":"user","text":"sent","createdAt":1,"requestId":"$id"}],"cursor":1,"hasMore":false,"activity":"idle"}"""
                        else -> return MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"error":{"code":"not_found","message":"synthetic","retryable":false}}""")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
            server.start()
            val other = attachment.copy(localId = "other", uploadId = "other-upload")
            val store = Store(StoredState(host = host(server.url("/").toString().removeSuffix("/")), selectedSessionId = "chat", drafts = mapOf("chat" to "sent"), pending = StoredCommand(id, "send", "chat", "sent", status = "uncertain", uploadIds = listOf("upload")), attachmentDrafts = mapOf("chat" to listOf(attachment, other))))
            val repo = NetworkMobileRepository(store, owner, true)
            try {
                repo.restore(); repo.setForeground(true)
                assertNull(store.value.pending)
                assertEquals(listOf(other), store.value.attachmentDrafts["chat"])
                assertEquals(ConnectionState.ONLINE, repo.state.value.connection)
            } finally { repo.close(); owner.cancel() }
        }
    }
}
