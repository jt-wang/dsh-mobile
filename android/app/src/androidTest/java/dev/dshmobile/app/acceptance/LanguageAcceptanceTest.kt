package dev.dshmobile.app.acceptance

import android.app.LocaleManager
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import dev.dshmobile.app.locale.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Disposable emulator only. Synthetic encrypted offline fixture, no host connection or real credentials. */
class LanguageAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("home_screen").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("settings").performClick()
    }
    private fun option(choice: LanguagePreference) = compose.onNodeWithTag("language_${choice.name.lowercase()}")
    private fun choose(choice: LanguagePreference) {
        option(choice).performScrollTo().performClick()
        compose.waitUntil(10_000) { runCatching { option(choice).assertIsSelected(); true }.getOrDefault(false) }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "language-2026-10-16").apply { mkdirs() }
        val image = instrumentation.uiAutomation.takeScreenshot()
        try { File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
    }
    private fun assertLanguage(choice: LanguagePreference) {
        scenario!!.onActivity {
            assertEquals(choice, languagePreference(it))
            if (choice != LanguagePreference.SYSTEM) assertEquals(choice.tag, it.resources.configuration.locales[0].language)
            else {
                val system = if (Build.VERSION.SDK_INT >= 33) {
                    val manager = it.getSystemService(LocaleManager::class.java)
                    assertTrue("System must clear platform pin", manager.applicationLocales.isEmpty)
                    manager.systemLocales
                } else {
                    assertFalse("System must remove preference", it.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).contains(LANGUAGE_KEY))
                    android.content.res.Resources.getSystem().configuration.locales
                }
                val expected = it.createConfigurationContext(android.content.res.Configuration(it.resources.configuration).apply { setLocales(system) })
                assertEquals(expected.getString(dev.dshmobile.app.R.string.mobile_language), it.getString(dev.dshmobile.app.R.string.mobile_language))
                assertEquals(system[0].language, it.resources.configuration.locales[0].language)
            }
        }
        option(choice).performScrollTo().assertIsSelected()
    }

    @Test fun foreignPinIsNotSystemAndSystemClearsIt() {
        // Same state as `cmd locale set-app-locales ... --locales uk` on 33+.
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("uk")
        else context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).edit().putString(LANGUAGE_KEY, "uk").commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario!!.onActivity {
                assertNull(languagePreference(it))
                it.setContent { dev.dshmobile.app.ui.MobileTheme {
                    dev.dshmobile.app.ui.settings.LanguageSettingsContent(languagePreference(it)) { choice -> setAppLanguage(it, choice) }
                } }
            }
            LanguagePreference.entries.forEach { option(it).assertIsNotSelected() }
            option(LanguagePreference.SYSTEM).performClick()
            scenario!!.onActivity {
                assertEquals(LanguagePreference.SYSTEM, languagePreference(it))
                if (Build.VERSION.SDK_INT >= 33) assertTrue(it.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
                else assertFalse(it.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).contains(LANGUAGE_KEY))
            }
        } finally { scenario?.close() }
    }

    @Test fun helperMatchesPlatformFallbackWithForeignPin() {
        if (Build.VERSION.SDK_INT < 33) return
        val previous = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        context.getSystemService(LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags("zz")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario!!.onActivity {
                val helper = appLanguageContext(it)
                val merged = helper.resources.configuration.locales
                it.getSystemService(LocaleManager::class.java).systemLocales.toLanguageTags().split(',').filter { tag -> tag.isNotEmpty() }.forEach { tag ->
                    assertTrue("helper retains system locale $tag", (0 until merged.size()).any { index -> merged[index].toLanguageTag() == tag })
                }
                assertEquals(it.getString(dev.dshmobile.app.R.string.mobile_language), helper.getString(dev.dshmobile.app.R.string.mobile_language))
            }
        } finally { context.getSystemService(LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(previous); scenario?.close() }
    }

    @Test fun languageJourney() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("languageStage") ?: "journey"
        if (stage == "journey") {
            val host = PairedHost(HostEndpoint("http://127.0.0.1:59999"), "synthetic-language-device", "synthetic-language-token-0000000000000000", "Synthetic computer")
            val now = System.currentTimeMillis()
            val session = SessionSummary("synthetic-chat", "Synthetic chat", "synthetic-project", now, false, true)
            EncryptedStateStore(context).write(StoredState(host, session.id, mapOf(session.id to "Unsent synthetic draft")))
            EncryptedHistoryStore(context).write(HistoryRecord(historyBinding(host), listOf(HistoryEntry(
                SessionSnapshot(session, listOf(ChatMessage("synthetic-message", "assistant", "Synthetic cached message", now)), 1, false, "idle"), now)),
                HistoryIndex(listOf(Workspace("synthetic-project", "Synthetic project", true)), listOf(session), false, now)))
            ensureNotificationChannels(appLanguageContext(context))
        }
        try {
            launch()
            if (stage != "journey") {
                val expected = requireNotNull(LanguagePreference.fromTag(InstrumentationRegistry.getArguments().getString("expectedLanguage")))
                if (stage == "select") choose(expected)
                assertLanguage(expected)
                return@runBlocking
            }
            choose(LanguagePreference.RUSSIAN)
            assertLanguage(LanguagePreference.RUSSIAN)
            compose.onNodeWithText("Язык").assertIsDisplayed()
            capture("settings-ru-api${Build.VERSION.SDK_INT}")
            scenario!!.close(); launch()
            assertLanguage(LanguagePreference.RUSSIAN)
            choose(LanguagePreference.ENGLISH)
            assertLanguage(LanguagePreference.ENGLISH)
            compose.onNodeWithText("Language").assertIsDisplayed()
            capture("settings-en-api${Build.VERSION.SDK_INT}")
            val manager = context.getSystemService(NotificationManager::class.java)
            assertEquals("Agent answers · With sound", manager.getNotificationChannel("answers_alert_v2").name.toString())
            scenario!!.close(); launch()
            assertLanguage(LanguagePreference.ENGLISH)
            compose.onNodeWithTag("settings_back").performClick()
            compose.onNodeWithText("Today").assertIsDisplayed()
            capture("chats-en-api${Build.VERSION.SDK_INT}")
            compose.onNodeWithTag("session_synthetic-chat").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("message_input").assertTextContains("Unsent synthetic draft")
            scenario!!.recreate()
            compose.onNodeWithTag("message_input").assertTextContains("Unsent synthetic draft")
            compose.onNodeWithTag("chat_drawer").performClick()
            compose.onNodeWithTag("settings").performClick()
            choose(LanguagePreference.SYSTEM)
            assertLanguage(LanguagePreference.SYSTEM)
            scenario!!.close(); launch()
            assertLanguage(LanguagePreference.SYSTEM)
            assertEquals("synthetic-language-device", EncryptedStateStore(context).read().host?.deviceId)
            assertEquals("Unsent synthetic draft", EncryptedStateStore(context).read().drafts["synthetic-chat"])
            assertEquals("Synthetic cached message", EncryptedHistoryStore(context).read().entries.single().snapshot.messages.single().text)
        } finally { scenario?.close() }
    }
}
