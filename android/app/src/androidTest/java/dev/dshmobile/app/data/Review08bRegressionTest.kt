package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Independent probes. Assertions intentionally assert required behavior, not the defect. */
class Review08bRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun rapidCancellationLeavesNoOrphanSummary() = runBlocking {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationPosting.cancelAll(context); delay(400)
        try {
            repeat(12) { round ->
                val child = NotificationCompat.Builder(context, "attention_silent_v2").setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Synthetic").setGroup(ALERT_GROUP).setSilent(true).build()
                manager.notify("a", 2, child); updateAlertSummary(context, added = "a" to child)
                manager.notify("b", 2, child); updateAlertSummary(context, added = "b" to child)
                withTimeout(3000) { while(manager.activeNotifications.count { it.id == 2 } != 2) delay(10) }
                NotificationPosting.cancel(context, "a", 2)
                NotificationPosting.cancel(context, "b", 2)
                delay(2000)
                assertEquals("round=$round: summary left after both children removed", 0, manager.activeNotifications.size)
            }
        } finally { NotificationPosting.cancelAll(context) }
    }
    @Test fun dismissalInvalidatesQueuedNotificationTicket() = runBlocking {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationPosting.cancelAll(context); delay(300)
        val ticket = NotificationPosting.ticket("dismissed", 2)
        try {
            NotificationDismissedReceiver().onReceive(context, Intent().putExtra("removedTag", "dismissed"))
            var committed = false
            NotificationPosting.commit(ticket) { committed = true }
            assertFalse("Dismissed child's already queued post remains valid", committed)
            val next = NotificationPosting.ticket("dismissed", 2)
            try { NotificationPosting.commit(next) { committed = true }; assertTrue("New event must still alert", committed) }
            finally { NotificationPosting.release(next) }
        } finally { NotificationPosting.release(ticket); NotificationPosting.cancelAll(context) }
    }
    @Test fun summaryRelocalizesWithChildren() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationPosting.cancelAll(context)
        ensureNotificationChannels(context)
        val prefs = context.getSharedPreferences(dev.dshmobile.app.locale.LANGUAGE_PREFERENCES, android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString(dev.dshmobile.app.locale.LANGUAGE_KEY, null)
        val locales = if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales else null
        fun pin(tag: String) {
            if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(tag)
            else prefs.edit().putString(dev.dshmobile.app.locale.LANGUAGE_KEY, tag).commit()
        }
        try {
            pin("en")
            val child = NotificationCompat.Builder(context, "attention_silent_v2").setSmallIcon(R.drawable.ic_launcher_foreground).setGroup(ALERT_GROUP).setSilent(true).build()
            manager.notify("language", 2, child); updateAlertSummary(context, added = "language" to child)
            withTimeout(3000) { while (manager.activeNotifications.none { it.id == 3 }) delay(20) }
            val summary = manager.activeNotifications.single { it.id == 3 }.notification
            assertEquals("Open the chat on your computer", summary.extras.getString(Notification.EXTRA_TEXT))
            val pendingIntent = summary.contentIntent
            pin("ru")
            refreshNotificationLanguagePass(context)
            withTimeout(10000) { while (manager.activeNotifications.single { it.id == 3 }.notification.extras.getString(Notification.EXTRA_TEXT) != "Откройте чат на компьютере") delay(50) }
            assertEquals(pendingIntent, manager.activeNotifications.single { it.id == 3 }.notification.contentIntent)
        } finally {
            NotificationPosting.cancelAll(context)
            if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = locales!!
            else prefs.edit().apply { if (previous == null) remove(dev.dshmobile.app.locale.LANGUAGE_KEY) else putString(dev.dshmobile.app.locale.LANGUAGE_KEY, previous) }.commit()
        }
    }
    private class Store(var value: StoredState): SecureStateStore {
        override suspend fun read() = value
        override suspend fun write(value: StoredState) { this.value = value }
        override suspend fun clear() { value = StoredState() }
    }
    @Test fun abandonUncertainThenRemoveDoesNotDeletePossiblyAdmittedUpload() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val deletes = java.util.concurrent.CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if(request.method == "DELETE") deletes.add(request.path!!)
                    return MockResponse().setResponseCode(204)
                }
            }
            server.start()
            val source = DraftAttachment("local", "content://synthetic/source", "test.txt", 4, "text/plain", "possibly-admitted", Long.MAX_VALUE)
            val store = Store(StoredState(host = PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")), "device", "synthetic-token-for-review-00000000", "Synthetic"),
                selectedSessionId = "chat", pending = StoredCommand(UUID.randomUUID().toString(), "send", "chat", "", status="uncertain", uploadIds=listOf("possibly-admitted"), attachmentSources=listOf(source)),
                attachmentDrafts=mapOf("chat" to listOf(source))))
            val repo = NetworkMobileRepository(store, owner, true)
            try {
                repo.restore(); repo.abandonPending()
                assertTrue(store.value.possiblyUsedUploads.containsKey("possibly-admitted"))
                repo.close()
                val restored = NetworkMobileRepository(store, owner, true)
                try { restored.restore(); restored.saveAttachments("chat", emptyList()) } finally { restored.close() }
                delay(700)
                assertTrue("DELETE attempted for upload referenced by an uncertain command: $deletes", deletes.isEmpty())
            } finally { repo.close(); owner.cancel() }
        }
    }
    @Test fun refreshFailureRetainsSuccessfullyReuploadedSources() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var uploads = 0
        val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
        MockWebServer().use { server ->
            server.dispatcher = object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    fun json(body: String, status: Int=200) = MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body)
                    return when(request.requestUrl!!.encodedPath) {
                        "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":true,"questions":false,"approvals":false,"push":false}}""")
                        "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                        "/v1/presets" -> json("""{"items":[]}""")
                        "/v1/sessions" -> json("""{"items":[$session],"nextCursor":null}""")
                        "/v1/sessions/chat" -> json("""{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"idle"}""")
                        "/v1/sessions/chat/messages" -> json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""",404)
                        "/v1/sessions/chat/uploads" -> {
                            uploads++
                            if(uploads == 1) json("""{"uploadId":"fresh-1","expiresAt":9999999999999,"attachment":{"id":"ref","name":"test.txt","size":4,"mime":"text/plain","kind":"file"}}""",201)
                            else json("""{"error":{"code":"rate_limited","message":"Synthetic","retryable":true}}""",429)
                        }
                        else -> json("""{"error":{"code":"not_found","message":"Synthetic","retryable":false}}""",404)
                    }
                }
            }
            server.start()
            val sources = listOf("one","two").map { DraftAttachment(it,"content://synthetic/$it","test.txt",4,"text/plain",it,Long.MAX_VALUE) }
            val store = Store(StoredState(host=PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")),"device","synthetic-token-for-review-00000000","Synthetic"),selectedSessionId="chat",attachmentDrafts=mapOf("chat" to sources)))
            val repo = NetworkMobileRepository(store,owner,true,openAttachment={ "test".byteInputStream() })
            try {
                repo.restore(); repo.setForeground(true)
                assertEquals(ConnectionState.ONLINE,repo.state.value.connection)
                repo.sendMessage("",null,listOf("one","two"))
                assertEquals(2,uploads)
                assertEquals("Successful first refreshed upload is lost when second upload fails", "fresh-1", store.value.attachmentDrafts["chat"]!!.first().uploadId)
            } finally { repo.close(); owner.cancel() }
        }
    }
}
