package dev.dshmobile.app.acceptance

import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import dev.dshmobile.app.locale.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

/** Running/stopped production service with synthetic pairing. Disposable emulator only. */
class NotificationLanguageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Active: " + context.getSystemService(NotificationManager::class.java).activeNotifications.joinToString { "${it.id}/${it.tag}: ${it.notification.extras.getString(Notification.EXTRA_TITLE)}" }, check())
    }

    @Test fun runningServiceRelocalizesNotificationsAndChannels() = exercise(stopped = false)
    @Test fun stoppedServiceRelocalizesNotificationsAndPublicVersion() = exercise(stopped = true)

    @Test fun bulkAlertsAllTranslateAndLatestLanguageWins() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        ensureNotificationChannels(appLanguageContext(context))
        val english = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.ENGLISH) })
        val old = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            else context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_KEY, null)
        fun pin(tag: String) {
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
            else context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).edit().putString(LANGUAGE_KEY, tag).commit()
        }
        try {
            pin("en")
            for (index in 0 until 20) {
                val public = NotificationCompat.Builder(english, "answers_silent_v2").setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("DSH Mobile").setContentText("Answer ready").build()
                manager.notify("bulk:$index:answers", 2, NotificationCompat.Builder(english, "answers_silent_v2")
                    .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Answer ready").setContentText("Open the chat on your computer")
                    .setPublicVersion(public).setOnlyAlertOnce(true).build())
                kotlinx.coroutines.delay(350) // Accumulated alerts, not a fixture burst rejected by Android.
            }
            await { manager.activeNotifications.count { it.tag?.startsWith("bulk:") == true } == 20 }
            pin("ru")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { refreshNotificationLanguage(context) }
            val concurrent = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                repeat(2) { index ->
                    postNotification(context, "normal:$index:answers", 2, NotificationCompat.Builder(english, "answers_silent_v2")
                        .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Answer ready")
                        .setContentText("Open the chat on your computer").addExtras(alertLanguageExtras("answers", null))
                        .setOnlyAlertOnce(true).build())
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 35_000
            while (SystemClock.elapsedRealtime() < deadline && manager.activeNotifications.count {
                    it.tag?.startsWith("bulk:") == true && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Ответ готов"
                } != 20) kotlinx.coroutines.delay(100)
            val alerts = manager.activeNotifications.filter { it.tag?.startsWith("bulk:") == true }
            assertEquals(20, alerts.size)
            alerts.forEach {
                assertEquals("Ответ готов", it.notification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals("Откройте этот чат в приложении", it.notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals("Ответ готов", it.notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
                assertEquals("answers_silent_v2", it.notification.channelId)
            }
            concurrent.join()
            await { manager.activeNotifications.count { it.tag?.startsWith("normal:") == true && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Ответ готов" } == 2 }
            pin("en"); refreshNotificationLanguage(context)
            pin("ru"); refreshNotificationLanguage(context)
            pin("en"); refreshNotificationLanguage(context)
            val latestDeadline = SystemClock.elapsedRealtime() + 35_000
            while (SystemClock.elapsedRealtime() < latestDeadline && manager.activeNotifications.count {
                    it.tag?.startsWith("bulk:") == true && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Answer ready"
                } != 20) kotlinx.coroutines.delay(100)
            assertEquals(20, manager.activeNotifications.count { it.tag?.startsWith("bulk:") == true && it.notification.extras.getString(Notification.EXTRA_TITLE) == "Answer ready" })
        } finally {
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(old.orEmpty())
            else context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).edit().apply { if (old == null) remove(LANGUAGE_KEY) else putString(LANGUAGE_KEY, old) }.commit()
            manager.cancelAll()
        }
    }

    @Test fun legacyConnectionWithoutMetadataTranslates() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("connection", "Connection", NotificationManager.IMPORTANCE_LOW))
        val preferences = context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        val old = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() else preferences.getString(LANGUAGE_KEY, null)
        try {
            manager.notify(1, NotificationCompat.Builder(context, "connection").setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("DSH Mobile monitoring").setContentText("Connection interrupted. Reconnecting; alerts may be delayed.").setOnlyAlertOnce(true).build())
            await { manager.activeNotifications.any { it.id == 1 && it.tag == null } }
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ru")
            else preferences.edit().putString(LANGUAGE_KEY, "ru").commit()
            refreshNotificationLanguage(context)
            await { manager.activeNotifications.find { it.id == 1 && it.tag == null }?.notification?.extras?.getString(Notification.EXTRA_TITLE) == appLanguageContext(context).getString(R.string.notif_ongoing_title_reconnecting) }
            assertEquals(appLanguageContext(context).getString(R.string.notif_ongoing_reconnecting_body), manager.activeNotifications.single { it.id == 1 && it.tag == null }.notification.extras.getString(Notification.EXTRA_TEXT))
        } finally {
            manager.cancel(1)
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(old.orEmpty())
            else preferences.edit().apply { if (old == null) remove(LANGUAGE_KEY) else putString(LANGUAGE_KEY, old) }.commit()
        }
    }

    @Test fun repeatedStartPreservesLastConnectionStatus() = runBlocking {
        val store = NotificationStore(context)
        val old = store.read()
        val state = EncryptedStateStore(context).read()
        val manager = context.getSystemService(NotificationManager::class.java)
        store.update { it.copy(enabled = false) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val intent = Intent(context, NotificationService::class.java)
        fun connection() = manager.activeNotifications.singleOrNull { it.id == 1 && it.tag == null }
        try {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:59999"),
                "synthetic-language-device", "synthetic-language-token-0000000000000000", "Synthetic computer")))
            store.update { it.copy(enabled = true, deviceId = "synthetic-language-device", settings = it.settings.copy(enabled = true)) }
            scenario.onActivity { it.startForegroundService(intent) }
            val offline = appLanguageContext(context).getString(R.string.notif_ongoing_title_reconnecting)
            await { connection()?.notification?.extras?.getString(Notification.EXTRA_TITLE) == offline }
            repeat(5) {
                val previous = connection()!!.postTime
                scenario.onActivity { it.startForegroundService(intent) }
                await { (connection()?.postTime ?: 0) > previous }
                assertEquals(offline, connection()!!.notification.extras.getString(Notification.EXTRA_TITLE))
                assertEquals(appLanguageContext(context).getString(R.string.notif_ongoing_reconnecting_body), connection()!!.notification.extras.getString(Notification.EXTRA_TEXT))
                assertEquals("offline", connection()!!.notification.extras.getString("dev.dshmobile.connectionStatus"))
                kotlinx.coroutines.delay(300)
            }
        } finally {
            context.stopService(intent)
            store.update { old }; EncryptedStateStore(context).write(state)
            scenario.close(); manager.cancelAll()
        }
    }

    @Test fun fastStopSurvivesWithAndWithoutBulkRefresh() = runBlocking {
        val store = NotificationStore(context)
        val old = store.read()
        val state = EncryptedStateStore(context).read()
        val manager = context.getSystemService(NotificationManager::class.java)
        store.update { it.copy(enabled = false) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val intent = Intent(context, NotificationService::class.java)
        val pid = android.os.Process.myPid()
        try {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:59999"),
                "synthetic-language-device", "synthetic-language-token-0000000000000000", "Synthetic computer")))
            store.update { it.copy(enabled = true, deviceId = "synthetic-language-device", settings = it.settings.copy(enabled = true)) }
            ensureNotificationChannels(context)
            for (bulk in listOf(false, true)) {
                if (bulk) repeat(20) { index ->
                    manager.notify("fast:$index:answers", 2, NotificationCompat.Builder(context, "answers_silent_v2")
                        .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("stale").setContentText("stale").build())
                    kotlinx.coroutines.delay(310)
                }
                repeat(10) {
                    if (bulk) refreshNotificationLanguage(context)
                    scenario.onActivity { it.startForegroundService(intent) }
                    kotlinx.coroutines.delay(60)
                    context.stopService(intent)
                    kotlinx.coroutines.delay(400)
                }
                kotlinx.coroutines.delay(7_000) // Allow Android's deferred foreground-start timeout to fire.
                assertEquals(pid, android.os.Process.myPid())
            }
        } finally {
            context.stopService(intent)
            store.update { old }; EncryptedStateStore(context).write(state)
            scenario.close(); manager.cancelAll()
        }
    }

    @Test fun queuedAlertDoesNotPostAfterChatOpenedAndCancelled() = runBlocking {
        val store = NotificationStore(context)
        val old = store.read()
        val state = EncryptedStateStore(context).read()
        val manager = context.getSystemService(NotificationManager::class.java)
        val chat = "queued-race-chat"
        val tag = "synthetic-language-device:$chat:answers"
        try {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:59999"),
                "synthetic-language-device", "synthetic-language-token-0000000000000000", "Synthetic computer")))
            val local = store.update { it.copy(enabled = true, deviceId = "synthetic-language-device", settings = it.settings.copy(enabled = true)) }
            ensureNotificationChannels(context)
            val service = NotificationService()
            android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(service, context)
            val event = NotificationEvent(1, "queued-race", 1, System.currentTimeMillis(), Long.MAX_VALUE, "synthetic-project", chat, "answer-finished", 1)
            val method = NotificationService::class.java.declaredMethods.single { it.name == "show" }.apply { isAccessible = true }
            VisibleNotificationChat.sessionId = null
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(service, "synthetic-language-device", event, local, false, continuation)
            }
            await { manager.activeNotifications.any { it.tag == tag } } // Positive control: this fixture really can deliver.
            NotificationPosting.cancel(context, tag, 2)
            NotificationPosting.paced { }
            val unaffected = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    method.invoke(service, "synthetic-language-device", event, local, false, continuation)
                }
            }
            store.update { it.copy(cursor = "unrelated-page-commit") }
            unaffected.join()
            await { manager.activeNotifications.any { it.tag == tag } } // A durable alert cannot be dropped by unrelated page/settings bookkeeping.
            NotificationPosting.cancel(context, tag, 2)
            for (change in listOf("visible", "cancel", "off", "disabled", "project", "chat", "unpair")) {
                store.update { local }
                VisibleNotificationChat.sessionId = null
                NotificationPosting.paced { } // Ensure delivery must wait in the production pacer.
                val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                        method.invoke(service, "synthetic-language-device", event, local, false, continuation)
                    }
                }
                when (change) {
                    "visible" -> { VisibleNotificationChat.sessionId = chat; manager.cancel(tag, 2); VisibleNotificationChat.sessionId = null }
                    "cancel" -> NotificationPosting.cancel(context, tag, 2)
                    "off" -> store.update { it.copy(answerMode = AlertMode.OFF) }
                    "disabled" -> store.update { it.copy(enabled = false) }
                    "project" -> store.update { it.copy(settings = it.settings.copy(projects = listOf(ProjectNotificationSetting("synthetic-project", false)))) }
                    "chat" -> store.update { it.copy(settings = it.settings.copy(chats = listOf(ChatNotificationSetting(chat, false)))) }
                    "unpair" -> EncryptedStateStore(context).write(StoredState())
                }
                queued.join()
                kotlinx.coroutines.delay(500)
                assertFalse(change, manager.activeNotifications.any { it.tag == tag })
            }
        } finally {
            VisibleNotificationChat.sessionId = null; manager.cancel(tag, 2)
            store.update { old }; EncryptedStateStore(context).write(state)
        }
    }

    private fun exercise(stopped: Boolean) = runBlocking {
        val preferences = context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        val previous = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            else preferences.getString(LANGUAGE_KEY, null)
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en")
        else preferences.edit().putString(LANGUAGE_KEY, "en").commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        val local = NotificationStore(context).read()
        val stored = EncryptedStateStore(context).read()
        EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:59999"),
            "synthetic-language-device", "synthetic-language-token-0000000000000000", "Synthetic computer")))
        NotificationStore(context).update { it.copy(enabled = true, deviceId = "synthetic-language-device", settings = it.settings.copy(enabled = true)) }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val service = Intent(context, NotificationService::class.java)
        val tag = "synthetic:chat:answers"
        try {
            scenario.onActivity { it.startForegroundService(service) }
            await { manager.activeNotifications.any { it.id == 1 && it.tag == null } }
            ensureNotificationChannels(appLanguageContext(context))
            val public = NotificationCompat.Builder(context, "answers_alert_v2").setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("DSH Mobile").setContentText("Answer ready").build()
            manager.notify(tag, 2, NotificationCompat.Builder(context, "answers_alert_v2")
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle("Answer ready")
                .setContentText("Open the chat on your computer").addExtras(alertLanguageExtras("answers", null))
                .setPublicVersion(public).setOnlyAlertOnce(true).build())
            await { manager.activeNotifications.any { it.tag == tag } }
            val original = manager.activeNotifications.single { it.tag == tag }.notification
            if (stopped) {
                assertTrue(context.stopService(service))
                await { context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(100).none { it.service.className == NotificationService::class.java.name } }
                NotificationStore(context).update { it.copy(enabled = false) } // Activity resume must not restart monitoring.
                manager.cancel(1) // stopService can detach (rather than remove) the foreground notification.
                // No activity/service alive: API 33+ must use the manifest receiver, not onResume.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            }
            for ((choice, title, generic, channel, monitoring) in listOf(
                listOf("ru", "Ответ готов", "Откройте этот чат в приложении", "Ответы агента · Со звуком", "Ответы с компьютера"),
                listOf("en", "Answer ready", "Open this chat in the app", "Agent answers · With sound", "Answers from your computer"))) {
                if (stopped && Build.VERSION.SDK_INT >= 33) {
                    context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice)
                } else {
                    if (stopped) scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    scenario.onActivity { setAppLanguage(it, requireNotNull(LanguagePreference.fromTag(choice))) }
                }
                await { manager.activeNotifications.find { it.id == 2 && it.tag == tag }?.notification?.extras?.getString(Notification.EXTRA_TITLE) == title }
                val alert = manager.activeNotifications.single { it.id == 2 && it.tag == tag }.notification
                assertEquals(generic, alert.extras.getString(Notification.EXTRA_TEXT))
                assertEquals(title, alert.publicVersion.extras.getString(Notification.EXTRA_TEXT))
                await { manager.getNotificationChannel("answers_alert_v2").name.toString() == channel }
                assertEquals(original.channelId, alert.channelId)
                assertEquals(original.`when`, alert.`when`)
                assertTrue(alert.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                assertNull(alert.sound)
                assertNull(alert.vibrate)
                if (!stopped) await { manager.activeNotifications.singleOrNull { it.id == 1 && it.tag == null }?.notification?.extras?.getString(Notification.EXTRA_TITLE) == appLanguageContext(context).getString(R.string.notif_ongoing_title_reconnecting) }
                else assertTrue(manager.activeNotifications.none { it.id == 1 && it.tag == null })
            }
        } finally {
            context.stopService(service)
            NotificationStore(context).update { local }
            EncryptedStateStore(context).write(stored)
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(previous.orEmpty())
            else if (previous == null) preferences.edit().remove(LANGUAGE_KEY).commit() else preferences.edit().putString(LANGUAGE_KEY, previous).commit()
            scenario.close()
            manager.cancelAll()
        }
    }
}
