package dev.dshmobile.app.acceptance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in full Activity -> ViewModel -> repository -> real host fixture server journey. */
class PhoneActivityFixtureTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun phone08ActivityJourney() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "phone08-config.json")
        assumeTrue("Opt-in isolated fixture config required", file.exists())
        val config = JSONObject(file.readText())
        runBlocking { EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint("http://127.0.0.1:${config.getInt("port")}"), config.getString("deviceId"), config.getString("deviceToken"), "Synthetic fixture"))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("session_chat").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("session_chat").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("message_input").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat_more").assertIsDisplayed()
            compose.onNodeWithTag("chat_more").performClick(); compose.onNodeWithTag("chat_model").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("model_vision").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("model_vision").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("model_vision").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("chat_more").performClick(); compose.onNodeWithTag("chat_rename").performClick()
            compose.onNodeWithTag("rename_input").performTextReplacement("Synthetic Activity rename"); compose.onNodeWithTag("rename_save").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("rename_input").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Synthetic Activity rename").assertIsDisplayed()
            compose.onNodeWithTag("message_input").performTextInput("Synthetic Activity message")
            compose.onNodeWithTag("send_message").performClick()
            // Questions can fill the small viewport below the message; lazy rows need an explicit scroll.
            compose.waitUntil(10000) { try {
                compose.onNodeWithTag("chat_timeline").performScrollToNode(hasText("Synthetic Activity message", substring = true))
                compose.onNodeWithText("Synthetic Activity message", substring = true).assertIsDisplayed(); true
            } catch (_: AssertionError) { false } }
        }
    }
}
