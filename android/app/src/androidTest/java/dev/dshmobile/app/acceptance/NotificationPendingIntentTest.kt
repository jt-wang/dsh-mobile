package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Fires the actual service-produced PendingIntent, independently of SystemUI hit testing. */
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class NotificationPendingIntentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: NotificationTestHost
    @org.junit.Before fun freshHost() { host = NotificationTestHost() }
    @org.junit.After fun stopHost() { host.close() }
    @Test fun coldActivity() = exercise("cold")
    @Test fun warmBackground() = exercise("warm")
    @Test fun foreground() = exercise("foreground")
    @Test fun zResolvedBeforeTap() = exercise("resolved")
    private fun exercise(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll(); VisibleNotificationChat.sessionId = null
        val service = Intent(context, NotificationService::class.java)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.startForegroundService(service) }
            compose.waitUntil(20000) { manager.activeNotifications.any { it.notification.extras.getBoolean("dev.dshmobile.questionAlert") } }
            val notification = manager.activeNotifications.first { it.notification.extras.getBoolean("dev.dshmobile.questionAlert") }.notification
            assertNotNull(notification.contentIntent)
            if (mode == "cold") scenario.close()
            if (mode == "warm") {
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
                compose.waitUntil(5000) { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
            }
            if (mode == "resolved") {
                val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
                val repository = NetworkMobileRepository(EncryptedStateStore(context), owner, true)
                try { runBlocking {
                    repository.setForeground(true); repository.restore(); repository.selectSession("chat")
                    repository.answer("question", dev.dshmobile.app.model.QuestionAnswer(listOf(dev.dshmobile.app.model.AnswerItem("q", listOf("Unit tests")))))
                    assertNull(repository.state.value.error)
                } } finally { repository.close(); owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
                scenario.close()
            }
            notificationTrace("test_pending_send_$mode")
            notification.contentIntent.send()
            val tag = if (mode == "resolved") "question_record_question" else "question_question"
            compose.waitUntil(15000) {
                // On API30 HOME -> PendingIntent can briefly have no registered composition.
                try { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                catch (_: IllegalStateException) { false }
            }
            compose.onNodeWithTag(tag).assertIsDisplayed()
            if (mode != "resolved") compose.onNodeWithTag("question_option_0").assertExists()
            val dump = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities")
            File(context.filesDir, "notification-activity-$mode.txt").outputStream().use { out -> android.os.ParcelFileDescriptor.AutoCloseInputStream(dump).use { it.copyTo(out) } }
            notificationTrace("test_question_visible_$mode")
            if (mode != "resolved") {
                compose.onNodeWithTag("question_option_0").performScrollTo().performClick()
                compose.onNodeWithTag("question_submit").assertIsEnabled()
            }
        } finally {
            scenario.close(); context.stopService(service); manager.cancelAll()
            // CLEAR_TOP recreates the standard-launchMode Activity; scenario owns the old instance.
            instrumentation.runOnMainSync {
                val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }
                    .filterIsInstance<MainActivity>().forEach { if (!it.isFinishing) it.finishAndRemoveTask() }
            }
            instrumentation.waitForIdleSync()
            VisibleNotificationChat.sessionId = null
        }
    }
}
