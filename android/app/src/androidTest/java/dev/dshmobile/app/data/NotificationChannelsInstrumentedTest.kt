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
    @Test fun languageRefreshPreservesExistingChannelSettings() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureNotificationChannels(context)
        val before = manager.getNotificationChannel("answers_silent_v2")
        val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("ru"))
        })
        refreshNotificationChannelNames(localized)
        val after = manager.getNotificationChannel("answers_silent_v2")
        assertEquals("Ответы агента · Без звука", after.name.toString())
        assertEquals(before.id, after.id)
        assertEquals(before.importance, after.importance)
        assertEquals(before.sound, after.sound)
        assertEquals(before.shouldVibrate(), after.shouldVibrate())
        assertEquals(before.description, after.description)
        assertEquals(before.lockscreenVisibility, after.lockscreenVisibility)
        refreshNotificationChannelNames(context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }))
        assertEquals("Agent answers · Silent", manager.getNotificationChannel("answers_silent_v2").name.toString())
    }

    @Test fun migratedAlertAndSilentChannelsRespectPrivacyAndReplaceWithoutAnotherAlert() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val existingAlert = manager.getNotificationChannel("answers_alert_v2")
        val existingSilent = manager.getNotificationChannel("answers_silent_v2")
        NotificationStore(context).update { it.copy(channelModesMigrated = false) }
        manager.createNotificationChannel(NotificationChannel("answers", "Legacy", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        ensureNotificationChannels(context)
        assertEquals(AlertMode.SILENT, NotificationStore(context).read().answerMode)
        assertNull(manager.getNotificationChannel("answers"))
        val alert = manager.getNotificationChannel("answers_alert_v2")
        val silent = manager.getNotificationChannel("answers_silent_v2")
        assertEquals(existingAlert?.importance ?: NotificationManager.IMPORTANCE_HIGH, alert.importance)
        if (existingAlert != null) assertEquals(existingAlert.sound, alert.sound) else assertNotNull(alert.sound)
        assertEquals(existingSilent?.importance ?: NotificationManager.IMPORTANCE_LOW, silent.importance)
        assertEquals(existingSilent?.sound, silent.sound) // Existing user overrides must survive migration too.
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
