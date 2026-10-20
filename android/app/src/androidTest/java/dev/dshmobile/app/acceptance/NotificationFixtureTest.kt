package dev.dshmobile.app.acceptance

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Requires StartupTimingTest pairing + isolated notification-capable fixture, never owner's device. */
class NotificationFixtureTest {
    @Test fun soundAndDegradedRecovery(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (InstrumentationRegistry.getArguments().getString("notificationFixture") == "true") {
            val config = org.json.JSONObject(java.io.File(context.filesDir, "phone08-config.json").readText())
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:${config.getInt("port")}"), config.getString("deviceId"), config.getString("deviceToken"), "Synthetic notification fixture")))
        }
        val host = EncryptedStateStore(context).read().host!!
        val store = NotificationStore(context)
        val old = store.read()
        ensureNotificationChannels(context)
        manager.cancelAll()
        VisibleNotificationChat.sessionId = null
        store.update { NotificationLocal(enabled = true, deviceId = host.deviceId,
            settings = NotificationSettings(enabled = true), dirty = true, channelModesMigrated = true) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val intent = Intent(context, NotificationService::class.java)
        suspend fun await(check: () -> Boolean) = withTimeout(15_000) { while (!check()) delay(50) }
        suspend fun control(path: String) = withContext(Dispatchers.IO) {
            val c = java.net.URL("http://127.0.0.1:18981/$path").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2000; c.readTimeout = 2000
            try { c.inputStream.use { it.readBytes() }; Unit } finally { c.disconnect() }
        }
        try {
            scenario.onActivity { it.startForegroundService(intent) }
            await { manager.activeNotifications.any { it.id == 1 && it.notification.extras.getString("dev.dshmobile.connectionStatus") in setOf("ready", "degraded") } }
            val connection = manager.getNotificationChannel("connection")
            // Android may promote an active foreground-service channel from MIN to LOW.
            assertTrue(connection.importance <= NotificationManager.IMPORTANCE_LOW)
            assertFalse(connection.canShowBadge())
            val alert = manager.getNotificationChannel("answers_alert_v2")
            assertNotNull(alert.sound)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, alert.importance)
            var previous: String? = null
            repeat(2) { index ->
                control("answer")
                await { manager.activeNotifications.any { it.id == 2 && it.notification.extras.getString("dev.dshmobile.alertEvent") != previous } }
                val shown = manager.activeNotifications.single { it.id == 2 }
                previous = shown.notification.extras.getString("dev.dshmobile.alertEvent")
                assertEquals("answers_alert_v2", shown.notification.channelId)
                assertEquals(0, shown.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
                Log.i("DSHFixtureTest", "sound_answer_${index + 1}_posted")
                delay(5000) // Leave record/audio evidence observable to adb dumpsys.
            }
            control("gap")
            await { manager.activeNotifications.any { it.id == 1 && it.notification.extras.getString("dev.dshmobile.connectionStatus") == "degraded" } }
            delay(3000)
            val notification = manager.activeNotifications.single { it.id == 1 }.notification
            assertFalse(notification.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("missed"))
            assertEquals(context.getString(dev.dshmobile.app.R.string.notif_ongoing_ready), notification.extras.getString(Notification.EXTRA_TEXT))
            Log.i("DSHFixtureTest", "degraded_silently_resynced")
        } finally {
            context.stopService(intent); scenario.close(); manager.cancelAll(); store.update { old }
        }
    }
}
