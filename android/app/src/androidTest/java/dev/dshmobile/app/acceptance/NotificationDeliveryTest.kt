package dev.dshmobile.app.acceptance

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production service boundary, including slow optional HTTP reads. */
class NotificationDeliveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun slowNamesDoNotGateFirstAttention() {
        NotificationTestHost().use { host ->
            host.namesDelayMs = 5000
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startForegroundService(Intent(context, NotificationService::class.java)) }
                compose.waitUntil(3000) { manager.activeNotifications.any { it.id == 2 } }
                assertNotNull(manager.activeNotifications.first { it.id == 2 }.notification.contentIntent)
            }
        }
    }
    @Test fun slowQuestionDoesNotBlockUnrelatedAnswerAndDismissPreventsEnrichmentResurrection() {
        NotificationTestHost().use { host ->
            host.slowEnrichment = true
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startForegroundService(Intent(context, NotificationService::class.java)) }
                compose.waitUntil(3000) { manager.activeNotifications.any { it.tag == "synthetic:chat:attention" } }
                assertTrue(host.enrichmentEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                NotificationPosting.cancel(context, "synthetic:chat:attention", 2)
                compose.waitUntil(2000) { manager.activeNotifications.any { it.tag == "synthetic:other:answers" } }
                // More than the enrichment timeout: neither the late response nor reconnect can restore it.
                compose.mainClock.autoAdvance = true
                Thread.sleep(3300)
                assertFalse(manager.activeNotifications.any { it.tag == "synthetic:chat:attention" })
            }
        }
    }
    @Test fun blockedConnectionLeavesHonestDismissibleNotificationWithOpenAppAndStop() {
        NotificationTestHost().use { host ->
            host.blocked = true
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val manager = context.getSystemService(NotificationManager::class.java)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.startForegroundService(Intent(context, NotificationService::class.java)) }
                compose.waitUntil(10000) { manager.activeNotifications.any { it.id == 1 && it.notification.extras.getString("dev.dshmobile.connectionStatus") == "blocked" } }
                val notification = manager.activeNotifications.single { it.id == 1 }.notification
                assertNotNull(notification.contentIntent); assertEquals(2, notification.actions.size)
                assertEquals(0, notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE))
                assertEquals(context.getString(R.string.notif_ongoing_title_blocked), notification.extras.getString(Notification.EXTRA_TITLE))
                notification.actions.first().actionIntent.send()
                compose.waitUntil(5000) { manager.activeNotifications.none { it.id == 1 } }
            }
        }
    }
}
