package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileTheme
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PhoneFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var oldTheme: String? = null
    @Before fun theme() {
        val prefs = context.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE)
        oldTheme = prefs.getString("theme", null)
        prefs.edit().putString("theme", InstrumentationRegistry.getArguments().getString("phoneTheme") ?: "DARK").commit()
    }
    @After fun restoreTheme() { context.getSharedPreferences("mobile_appearance", android.content.Context.MODE_PRIVATE).edit().apply { if (oldTheme == null) remove("theme") else putString("theme", oldTheme) }.commit() }
    private fun text(id: Int) = context.getString(id)
    private fun content(fullScreen: Boolean = false, block: @Composable () -> Unit) {
        compose.setContent {
            val activityResultOwner = requireNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides activityResultOwner) { MobileTheme {
            val args = InstrumentationRegistry.getArguments()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, args.getString("phoneScale")?.toFloatOrNull() ?: 1f)) {
                Box(if (fullScreen) Modifier.fillMaxSize() else Modifier.width(args.getString("phoneWidth")?.toIntOrNull()?.dp ?: 411.dp).fillMaxHeight().padding(16.dp)) { block() }
            }
        } } }
    }
    private fun capture(name: String) {
        val args = InstrumentationRegistry.getArguments(); val suffix = args.getString("phoneCapture") ?: return
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        // Compose's virtual clock does not wait for the platform sheet window's first frame.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(250, 5000)
        val dir = File(context.filesDir, "phone08").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            File(dir, "$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
    }
    @Test fun sendRequiresSeparateMenuTapAndDefaultsToQueue() {
        val sent = mutableListOf<String?>()
        content { Column { Spacer(Modifier.weight(1f)); SendControl(true, true, true) { sent += it } } }
        compose.onNodeWithTag("send_message").performTouchInput { longClick() }
        assertTrue(sent.isEmpty()); compose.onNodeWithTag("send_steer").assertIsDisplayed(); capture("send-menu")
        compose.onNodeWithTag("send_steer").performClick(); assertEquals(listOf("steer"), sent)
        compose.onNodeWithTag("send_message").performClick(); assertEquals(listOf("steer", null), sent)
    }
    @Test fun queueSteerRemoveAndReadOnly() {
        val actions = mutableListOf<Pair<String, String>>(); var enabled by mutableStateOf(true)
        content { Column { Spacer(Modifier.weight(1f)); QueueGroup(listOf(QueueItem("one", "Check the light theme", "queue"), QueueItem("two", "Attach both screenshots", "queue")), enabled, true, null, null, false) { id, action -> actions += id to action } } }
        compose.onNodeWithTag("queue_steer_one").performClick(); compose.onNodeWithTag("queue_remove_two").performClick()
        assertEquals(listOf("one" to "steer", "two" to "remove"), actions); capture("queue")
        compose.runOnIdle { enabled = false }; compose.onNodeWithTag("queue_steer_one").assertIsNotEnabled(); compose.onNodeWithTag("queue_remove_two").assertIsNotEnabled()
    }
    private fun question(multi: Boolean = false) = PendingQuestion("call", "open", listOf(QuestionItem("q", "Which checks should run?", "Checks", options = listOf(QuestionOption("Unit tests"), QuestionOption("Emulator")), multiSelect = multi, allowFreeText = true)))
    @Test fun singleQuestionStartsUnselectedAndSubmitsExactLabel() {
        var result: QuestionAnswer? = null
        var restored by mutableStateOf<QuestionAnswer?>(null)
        content { QuestionBlock(question(), restored, false, true, false, false, onChange = {}, onSubmit = { result = it }) }
        compose.onNodeWithTag("question_submit").assertIsNotEnabled()
        compose.runOnIdle { restored = QuestionAnswer(listOf(AnswerItem("q", listOf("Unit tests")))) }
        compose.onNodeWithTag("question_option_0").assertIsSelected()
        compose.onNodeWithTag("question_option_0").performClick(); capture("question-single")
        compose.onNodeWithTag("question_submit").performClick(); assertEquals(listOf("Unit tests"), result!!.answers.single().selected)
    }
    @Test fun multiQuestionAndCustomAnswerArePreserved() {
        var result: QuestionAnswer? = null
        content { QuestionBlock(question(true), null, false, true, false, false, onChange = {}, onSubmit = { result = it }) }
        compose.onNodeWithTag("question_option_0").performClick(); compose.onNodeWithTag("question_option_1").performClick()
        compose.onNodeWithTag("question_custom").performTextInput("Lint")
        capture("question-multi"); compose.onNodeWithTag("question_submit").performClick()
        assertEquals(listOf("Unit tests", "Emulator"), result!!.answers.single().selected); assertEquals("Lint", result!!.answers.single().custom)
    }
    @Test fun expiredQuestionCannotSubmit() {
        content { QuestionBlock(question(), null, false, true, false, false, expired = true, onChange = {}, onSubmit = { fail("expired") }) }
        compose.onNodeWithText(text(R.string.mobile_question_expired)).assertIsDisplayed(); compose.onNodeWithTag("question_submit").assertDoesNotExist(); capture("question-expired")
    }
    @Test fun recommendedNeverPreselectsAndSkipIsExplicit() {
        var skipped = false
        val pending = question().copy(questions = question().questions.map { it.copy(options = listOf(QuestionOption("Unit tests (Recommended)", recommended = true))) })
        content { QuestionBlock(pending, null, false, true, false, false, skipSupported = true, onSkip = { skipped = true }, onChange = {}, onSubmit = {}) }
        compose.onNodeWithText(text(R.string.mobile_question_recommended)).assertIsDisplayed()
        compose.onNodeWithText("Unit tests").assertIsDisplayed(); compose.onNodeWithTag("question_submit").assertIsNotEnabled()
        compose.onNodeWithTag("question_skip").performClick(); assertTrue(skipped)
    }
    @Test fun fileDownloadActionIsCapabilityGatedAndSteeredMarkerIsExplicit() {
        var opened = false
        val ref = AttachmentRef("file", "synthetic.txt", 19, "text/plain", "file")
        val cache = ImageMemoryCache { byteArrayOf() }
        content { MediaMessage(ChatMessage("m", "user", "", 1, delivery = "steer", attachments = listOf(ref)), cache, {}, onFile = { opened = true }) { _, _ -> } }
        compose.onNodeWithText(text(R.string.mobile_message_steered)).assertIsDisplayed()
        compose.onNodeWithTag("file_open_file").performClick(); assertTrue(opened)
    }
    @Test fun continuedQuestionRemainsAnswerable() {
        content { QuestionBlock(question().copy(state = "continued"), null, false, true, false, false, skipSupported = true, onChange = {}, onSubmit = {}) }
        compose.onNodeWithTag("question_submit").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("question_skip").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(text(R.string.mobile_question_expired)).assertDoesNotExist()
    }
    @Test fun attachmentErrorHasRetryAndRemove() {
        var retried = ""; var removed = ""
        content { Column { Spacer(Modifier.weight(1f)); AttachmentChips(listOf(UploadState(DraftAttachment("file", "content://synthetic/file", "test.txt", 48 * 1024, "text/plain"), error = true)), false, { removed = it }, { retried = it }) } }
        compose.onNodeWithTag("attachment_file").performClick(); assertEquals("file", retried); capture("attachment-error")
        compose.onNodeWithTag("attachment_remove_file").performClick(); assertEquals("file", removed)
    }
    @Test fun modelSheetExposesImagesMarkerAndSelectsModel() {
        var selected: ModelSelection? = null
        content { ModelSheet(ModelCatalog(listOf(CatalogModel("fixture", "text", "Text model", false), CatalogModel("fixture", "vision", "Vision model", true))), ModelSelection("fixture", "text"), false, false, false, true, {}, {}) { selected = it } }
        compose.onNodeWithContentDescription(text(R.string.mobile_model_supports_images)).assertIsDisplayed(); capture("models")
        compose.onNodeWithTag("model_vision").performClick(); assertEquals(ModelSelection("fixture", "vision"), selected)
    }
    @Test fun renameRejectsBlankAndOversizedUtf8Title() {
        var saved = ""
        content { RenameDialog("Synthetic chat", false, false, {}) { saved = it } }
        compose.onNodeWithTag("rename_input").performTextReplacement("я".repeat(129)); compose.onNodeWithTag("rename_save").assertIsNotEnabled()
        compose.onNodeWithTag("rename_input").performTextReplacement("New title"); capture("rename")
        compose.onNodeWithTag("rename_save").performClick(); assertEquals("New title", saved)
    }
    @Test fun viewerOpensAndCloses() {
        val image = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); image.recycle()
        var open by mutableStateOf(false)
        val ref = AttachmentRef("image", "synthetic.png", bytes.size.toLong(), "image/png", "image")
        val cache = ImageMemoryCache { bytes }
        content(fullScreen = true) { if (open) ImageViewer(listOf(ref), 0, cache, 1) { open = false } else MediaMessage(ChatMessage("m", "assistant", "Synthetic image", 1, attachments = listOf(ref)), cache, {}) { _, _ -> open = true } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media_image").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media_image").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) } }
        compose.waitForIdle(); compose.onNodeWithTag("media_image").performClick()
        compose.onNodeWithTag("image_viewer").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("image_viewer").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("viewer_close").assertIsDisplayed(); capture("viewer")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(3000) { compose.onAllNodesWithTag("image_viewer").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("image_viewer").assertDoesNotExist()
    }
}
