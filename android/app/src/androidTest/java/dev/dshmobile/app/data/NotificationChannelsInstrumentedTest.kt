package dev.dshmobile.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic content on disposable emulator only. Never run against the paired owner's phone. */
@RunWith(AndroidJUnit4::class)
class NotificationChannelsInstrumentedTest {
    @Test fun migratedAlertAndSilentChannelsRespectPrivacyAndReplaceWithoutAnotherAlert() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        NotificationStore(context).update { it.copy(channelModesMigrated = false) }
        manager.createNotificationChannel(NotificationChannel("answers", "Legacy", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        ensureNotificationChannels(context)
        assertEquals(AlertMode.SILENT, NotificationStore(context).read().answerMode)
        assertNull(manager.getNotificationChannel("answers"))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("answers_alert_v2").importance)
        assertNotNull(manager.getNotificationChannel("answers_alert_v2").sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("answers_silent_v2").importance)
        assertNull(manager.getNotificationChannel("answers_silent_v2").sound)
        val public = NotificationCompat.Builder(context, "answers_alert_v2").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("DSH Mobile").setContentText("Synthetic answer ready").build()
        val first = NotificationCompat.Builder(context, "answers_alert_v2").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Synthetic answer ready").setContentText("Synthetic project · Synthetic chat")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        manager.notify("synthetic-chat", 222, first)
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (manager.activeNotifications.none { it.tag == "synthetic-chat" } && android.os.SystemClock.elapsedRealtime() < deadline)
            android.os.SystemClock.sleep(50)
        val shown = manager.activeNotifications.single { it.tag == "synthetic-chat" }
        android.os.SystemClock.sleep(300)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.cacheDir, "synthetic-headsup.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, shown.notification.visibility)
        assertEquals("Synthetic answer ready", shown.notification.publicVersion.extras.getCharSequence("android.text"))
        manager.notify("synthetic-chat", 222, NotificationCompat.Builder(context, "answers_silent_v2")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Synthetic update")
            .setOnlyAlertOnce(true).setSilent(true).build())
        assertEquals(1, manager.activeNotifications.count { it.tag == "synthetic-chat" })
        manager.cancel("synthetic-chat", 222)
    }
}
