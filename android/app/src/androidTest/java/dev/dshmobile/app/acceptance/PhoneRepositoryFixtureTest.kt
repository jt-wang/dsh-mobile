package dev.dshmobile.app.acceptance

import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Production repository and HTTP transport against the real host server with a synthetic adapter. */
class PhoneRepositoryFixtureTest {
    @Test fun expiredUploadRefreshContinuesOriginalSendOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.filesDir, "phone08-config.json")
        assumeTrue("Opt-in isolated fixture config required", configFile.exists())
        val config = JSONObject(configFile.readText())
        val store = object : SecureStateStore {
            var value = StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:${config.getInt("port")}"), config.getString("deviceId"), config.getString("deviceToken"), "Synthetic fixture"))
            override suspend fun read() = value
            override suspend fun write(value: StoredState) { this.value = value }
            override suspend fun clear() { value = StoredState() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = NetworkMobileRepository(store, scope, true)
        val file = File(dev.dshmobile.app.ui.chat.TemporaryMedia.directory(context), "camera-synthetic-refresh.txt").apply { writeText("Synthetic refresh") }
        try {
            repository.setForeground(true); repository.restore(); repository.selectSession("chat")
            withTimeout(10000) { repository.state.first { it.connection == ConnectionState.ONLINE && !it.busy && it.snapshot != null } }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", file)
            val source = DraftAttachment("refresh", uri.toString(), file.name, file.length(), "text/plain", temporary = true)
            val uploaded = repository.upload("chat", source, { file.inputStream() }, {})
            val expired = uploaded.copy(expiresAt = 1)
            repository.saveAttachments("chat", listOf(expired))
            val editor = dev.dshmobile.app.ui.chat.PhoneEditor(context, repository, scope)
            withContext(Dispatchers.Main) {
                editor.restore(repository.state.value)
                assertTrue(editor.prepareSend("chat", listOf("refresh")))
                assertTrue(editor.inSession("chat").single().attachment.expiresAt > System.currentTimeMillis())
            }
            val sentText = "Synthetic expired upload send ${java.util.UUID.randomUUID()}"
            repository.sendMessage(sentText, "queue", editor.inSession("chat").map { it.attachment.uploadId!! })
            assertNull(repository.state.value.error); assertNull(repository.state.value.pending)
            assertEquals(1, repository.state.value.snapshot!!.messages.count { it.text == sentText })
            withContext(Dispatchers.Main) { editor.clear("chat") }
            withTimeout(5000) { while (file.exists()) delay(20) }
        } finally { repository.close(); scope.cancel(); file.delete() }
    }
    @Test fun phone08HostJourney() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "phone08-config.json")
        assumeTrue("Opt-in isolated fixture config required", file.exists())
        val config = JSONObject(file.readText())
        val store = object : SecureStateStore {
            var value = StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:${config.getInt("port")}"), config.getString("deviceId"), config.getString("deviceToken"), "Synthetic fixture"))
            override suspend fun read() = value
            override suspend fun write(value: StoredState) { this.value = value }
            override suspend fun clear() { value = StoredState() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = NetworkMobileRepository(store, scope, true)
        try {
            repository.setForeground(true); repository.restore(); repository.selectSession("chat")
            withTimeout(10000) { repository.state.first { it.connection == ConnectionState.ONLINE && it.snapshot?.session?.id == "chat" && !it.busy } }
            assertTrue(repository.state.value.capabilities!!.attachments)
            assertEquals("question", repository.state.value.snapshot!!.pendingQuestions.single().callId)
            assertTrue(repository.models().items.any { it.supportsImages == true })
            repository.rename("Synthetic Android rename"); assertNull(repository.state.value.error); assertEquals("Synthetic Android rename", repository.state.value.snapshot!!.session.title)
            repository.selectModel(ModelSelection("fixture", "vision")); assertNull(repository.state.value.error)
            repository.queueAction("queue-item", "steer"); assertNull(repository.state.value.error)
            repository.queueAction("queue-item", "remove"); assertNull(repository.state.value.error)
            val answer = QuestionAnswer(listOf(AnswerItem("q", listOf("Unit tests", "Emulator"), "Lint")))
            repository.answer("question", answer); assertNull(repository.state.value.error)
            val bytes = repository.image("chat", "synthetic-image"); assertTrue(bytes.isNotEmpty())
            val attachment = repository.upload("chat", DraftAttachment("local", "content://synthetic/image", "synthetic.png", bytes.size.toLong(), "image/png"), { bytes.inputStream() }, {})
            repository.saveAttachments("chat", listOf(attachment)); assertNotNull(store.value.attachmentDrafts["chat"])
            repository.sendMessage("", "steer", listOf(attachment.uploadId!!)); assertNull(repository.state.value.error)
            assertTrue(repository.state.value.snapshot!!.messages.any { it.role == "user" && it.attachments.isNotEmpty() })
            assertTrue(store.value.attachmentDrafts.isEmpty()); assertNull(store.value.pending)
        } finally { repository.close(); scope.cancel() }
    }
}
