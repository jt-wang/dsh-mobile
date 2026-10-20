package dev.dshmobile.app.acceptance

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import dev.dshmobile.app.MainActivity
import org.junit.Rule
import org.junit.Test

/** Launch/UI-ready wall clock in a new instrumentation process; synthetic paired fixture only. */
class ActivityStartupTimingTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun homeAndChatVisible() {
        val started = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithTag("session_demo-session").fetchSemanticsNodes().isNotEmpty() }
            Log.i("DSHPerfUI", "list_visible_ms=${SystemClock.elapsedRealtime() - started}")
            compose.onNodeWithTag("session_demo-session").assertIsDisplayed()
            compose.waitUntil(15000) {
                compose.onAllNodes(hasTestTag("connection_state") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(dev.dshmobile.app.R.string.mobile_pc_online))).fetchSemanticsNodes().isNotEmpty()
            }
            Log.i("DSHPerfUI", "online_visible_ms=${SystemClock.elapsedRealtime() - started}")
            val opened = SystemClock.elapsedRealtime()
            compose.onNodeWithTag("session_demo-session").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("This is a synthetic conversation. No model or DSH runtime is connected.").fetchSemanticsNodes().isNotEmpty() }
            Log.i("DSHPerfUI", "chat_visible_ms=${SystemClock.elapsedRealtime() - opened}")
        } finally { scenario.close() }
    }
}
