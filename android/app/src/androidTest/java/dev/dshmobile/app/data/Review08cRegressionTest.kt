package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class Review08cRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun malformedProtectionEntryDoesNotDestroyCoreState() {
        val text = """{"selectedSessionId":"chat","drafts":{"chat":"keep"},"possiblyUsedUploads":{"good":9999999999999,"bad":"not-a-timestamp"}}"""
        val restored = decodeStoredState(text)
        assertEquals("chat", restored.selectedSessionId)
        assertEquals("keep", restored.drafts["chat"])
        assertEquals(9999999999999L, restored.possiblyUsedUploads["good"])
    }
    @Test fun protectionRestoreIsBoundedAndDropsExpiredEntries() {
        val restored = decodeStoredState(mobileJson.encodeToString(StoredState(possiblyUsedUploads =
            (1..1300).associate { "id-$it" to 9999999999999L } + ("expired" to 1L))))
        assertTrue("Restored protection size=${restored.possiblyUsedUploads.size}", restored.possiblyUsedUploads.size <= 1280)
        assertFalse(restored.possiblyUsedUploads.containsKey("expired"))
    }
    private fun child() = NotificationCompat.Builder(context, "attention_silent_v2")
        .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Synthetic")
        .setGroup(ALERT_GROUP).setSilent(true).build()
    @Test fun bootstrapAfterProcessDeathThenSequentialCancel() = runBlocking {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationPosting.cancelAll(context); delay(400)
        try {
            val n = child()
            manager.notify("recovered-a",2,n); manager.notify("recovered-b",2,n)
            withTimeout(3000) { while(manager.activeNotifications.count { it.id == 2 } != 2) delay(20) }
            // Reset ONLY the process-local cache, retaining actual platform state.
            synchronized(NotificationPosting) {
                Class.forName("dev.dshmobile.app.data.NotificationGroupingKt").getDeclaredField("liveAlerts").apply { isAccessible = true }.set(null,null)
            }
            NotificationPosting.cancel(context,"recovered-a",2)
            NotificationPosting.cancel(context,"recovered-b",2)
            delay(700)
            assertTrue(manager.activeNotifications.isEmpty())
        } finally { NotificationPosting.cancelAll(context) }
    }
    @Test fun threadedCancellationInvalidatesLatePacedPostAndSummary() = runBlocking {
        ensureNotificationChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationPosting.cancelAll(context); delay(400)
        try {
            repeat(12) { round ->
                val n = child()
                val ticket = NotificationPosting.ticket("late-$round",2)
                NotificationPosting.recordForegroundPost()
                val posting = launch(Dispatchers.Default) {
                    try { NotificationPosting.paced { NotificationPosting.commit(ticket) {
                        manager.notify(ticket.tag,2,n); updateAlertSummary(context, added = ticket.tag!! to n)
                    } } } finally { NotificationPosting.release(ticket) }
                }
                withContext(Dispatchers.IO) { NotificationDismissedReceiver().onReceive(context,Intent().putExtra("removedTag",ticket.tag)) }
                posting.join()
            }
            delay(700)
            assertTrue("Late paced post resurrected an alert or summary",manager.activeNotifications.isEmpty())
        } finally { NotificationPosting.cancelAll(context) }
    }
    @Test fun newEventAfterDismissUsesAudibleChannel() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        val oldState = EncryptedStateStore(context).read()
        val store = NotificationStore(context); val oldLocal = store.read()
        NotificationPosting.cancelAll(context)
        try {
            ensureNotificationChannels(context)
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:59999"),"synthetic","synthetic-review-token-0000000000","Synthetic")))
            val local = store.update { NotificationLocal(enabled=true, deviceId="synthetic",settings=NotificationSettings(enabled=true),channelModesMigrated=true) }
            val service = NotificationService()
            android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible=true }.set(service,context)
            val show = NotificationService::class.java.declaredMethods.single { it.name == "show" }.apply { isAccessible=true }
            VisibleNotificationChat.sessionId = null
            suspend fun deliver(id: String) {
                val event = NotificationEvent(1,id,1,System.currentTimeMillis(),Long.MAX_VALUE,"w","chat","answer-finished",1)
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    show.invoke(service,"synthetic",event,local,false,continuation)
                }
            }
            deliver("event-one")
            withTimeout(3000) { while(manager.activeNotifications.none { it.id == 2 }) delay(20) }
            NotificationDismissedReceiver().onReceive(context, Intent().putExtra("removedTag","synthetic:chat:answers"))
            deliver("event-two")
            withTimeout(3000) { while(manager.activeNotifications.none { it.id == 2 && it.notification.extras.getString("dev.dshmobile.alertEvent") == "event-two" }) delay(20) }
            val n = manager.activeNotifications.single { it.id == 2 }.notification
            assertEquals("answers_alert_v2",n.channelId)
            assertEquals(0,n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertNotNull(manager.getNotificationChannel(n.channelId).sound)
        } finally { NotificationPosting.cancelAll(context); store.update { oldLocal }; EncryptedStateStore(context).write(oldState) }
    }
}
