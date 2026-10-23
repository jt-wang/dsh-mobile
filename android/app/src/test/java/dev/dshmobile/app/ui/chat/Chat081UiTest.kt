package dev.dshmobile.app.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.PromptContent
import dev.dshmobile.app.ui.components.hasPromptContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Chat081UiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val file = AttachmentRef("file", "notes.txt", 10, "text/plain", "file")
    private fun SemanticsNodeInteraction.performCustomAction(action: (List<androidx.compose.ui.semantics.CustomAccessibilityAction>) -> Unit) {
        val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { action(actions) }
    }
    private val session = SessionSummary("s", "Synthetic", "w", 1, false, true)

    @Test fun `attachment only pending prompt is visible in timeline and accepted content`() {
        val pending = PendingCommand("p", "send", "s", "", "sending", 2)
        assertTrue(hasPromptContent(pending))
        assertFalse(hasPromptContent(pending.copy(attachmentCount = 0)))
        compose.setContent { MaterialTheme {
            ChatTimeline(SessionSnapshot(session, emptyList(), 1, false, "idle"), pending, {})
        } }
        compose.onNodeWithTag("prompt_attachments_p").assertTextEquals(app.getString(R.string.mobile_prompt_attachments, 2))
    }

    @Test fun `sending and young pending are neutral but genuine uncertainty warns`() {
        var status by mutableStateOf("sending")
        compose.setContent { MaterialTheme {
            ChatTimeline(SessionSnapshot(session, emptyList(), 1, false, "idle"),
                PendingCommand("p", "send", "s", "Synthetic task", status), {})
        } }
        compose.onNodeWithText(app.getString(R.string.mobile_delivery_pending)).assertExists()
        compose.onNodeWithText(app.getString(R.string.mobile_unconfirmed_message)).assertDoesNotExist()
        compose.runOnIdle { status = "pending" }
        compose.onNodeWithText(app.getString(R.string.mobile_delivery_pending)).assertExists()
        compose.onNodeWithText(app.getString(R.string.mobile_unconfirmed_message)).assertDoesNotExist()
        compose.runOnIdle { status = "uncertain" }
        compose.onNodeWithText(app.getString(R.string.mobile_unconfirmed_message)).assertExists()
    }

    @Test fun `accepted attachment content includes count even without text`() {
        compose.setContent { MaterialTheme { PromptContent(PendingCommand("p", "send", "s", null, "accepted", 3)) } }
        compose.onNodeWithTag("prompt_attachments_p").assertTextEquals(app.getString(R.string.mobile_prompt_attachments, 3))
    }

    @Test fun `attachment names appear for pending timeline and accepted prompts without text`() {
        var accepted by mutableStateOf(false)
        val command = PendingCommand("p", "send", "s", "", "sending", 2, attachmentNames = listOf("notes.txt", "Фото 1.jpg"))
        compose.setContent { MaterialTheme {
            if (accepted) PromptContent(command.copy(status = "accepted"))
            else ChatTimeline(SessionSnapshot(session, emptyList(), 1, false, "idle"), command, {})
        } }
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText("Фото 1.jpg").assertExists()
        compose.runOnIdle { accepted = true }
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText("Фото 1.jpg").assertExists()
    }

    @Test fun `media message retains markdown copy as text selection and provisional marker`() {
        val message = ChatMessage("m", "assistant", "**Bold**", 1, provisional = true, attachments = listOf(file))
        var copied = ""
        compose.setContent { MaterialTheme { MediaMessage(message, ImageMemoryCache { byteArrayOf() }, { copied = it }) { _, _ -> } } }
        compose.onNodeWithText("Bold").assertExists()
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText(app.getString(R.string.mobile_provisional)).assertExists()
        compose.onNodeWithTag("copy:m").performClick()
        assertEquals("**Bold**", copied)
        compose.onNodeWithTag("message:m").performCustomAction { actions ->
            actions.single { it.label == app.getString(R.string.mobile_copy_as_text) }.action()
        }
        assertEquals("Bold", copied)
    }

    @Test fun `media timeline retains search highlights`() {
        val message = ChatMessage("m", "assistant", "find me", 1, attachments = listOf(file))
        compose.setContent { MaterialTheme {
            ChatTimeline(SessionSnapshot(session, listOf(message), 1, false, "idle"), null, {}, searchQuery = "find",
                media = { MediaAttachments(it, ImageMemoryCache { byteArrayOf() }) { _, _ -> } })
        } }
        val text = compose.onNodeWithTag("search_text:m", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.spanStyles.any { it.start == 0 && it.end == 4 })
    }

    @Test fun `closing viewer preserves scrolled timeline position`() {
        val messages = (1..40).map { ChatMessage("m$it", "assistant", "Message $it\n" + "line\n".repeat(5), it.toLong()) }
        var open by mutableStateOf(false)
        val ref = AttachmentRef("image", "image.jpg", 10, "image/jpeg", "image")
        compose.setContent { MaterialTheme {
            ChatTimeline(SessionSnapshot(session, messages, 1, false, "idle"), null, {})
            if (open) ChatViewerOverlay(listOf(ref), 0, ImageMemoryCache { throw java.io.IOException() }) { open = false }
        } }
        compose.waitUntil { compose.onAllNodesWithTag("chat_classification_loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("chat_timeline").performScrollToIndex(3)
        val before = compose.onNodeWithTag("message:m4").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("image_viewer").assertExists()
        compose.runOnIdle { open = false }
        compose.onNodeWithTag("image_viewer").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithTag("message:m4").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun `queued attachment displays pending state and can be removed`() {
        var removed: String? = null
        val source = DraftAttachment("queued", "content://synthetic/photo", "Photo 1.jpg", 10, "image/jpeg")
        compose.setContent { MaterialTheme { AttachmentChips(listOf(UploadState(source, progress = -1, uploading = true)), false, { removed = it }, {}) } }
        compose.onNodeWithText(app.getString(R.string.mobile_attach_waiting), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("attachment_queued").assertContentDescriptionEquals(app.getString(R.string.mobile_attach_pending, source.name))
        compose.onNodeWithTag("attachment_remove_queued").performClick()
        assertEquals("queued", removed)
    }

    @Test fun `permanently denied camera offers app settings`() {
        app.getSharedPreferences("attachment-permissions", Application.MODE_PRIVATE).edit().putBoolean("camera-requested", true).commit()
        compose.setContent { MaterialTheme { AttachmentPicker({}, 0) { _, _ -> } } }
        compose.onNodeWithText(app.getString(R.string.mobile_attach_camera)).performClick()
        compose.onNodeWithTag("attachment_camera_settings").assertExists().performClick()
        val intent = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
    }

    @Test fun `touch exploration shows previous and next controls`() {
        val manager = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        val refs = (1..5).map { AttachmentRef("$it", "$it.jpg", 10, "image/jpeg", "image") }
        compose.setContent { MaterialTheme { ImageViewer(refs, 3, ImageMemoryCache { throw java.io.IOException() }) {} } }
        compose.onNodeWithTag("viewer_next").assertIsDisplayed().performClick()
        compose.onNodeWithTag("viewer_next").assertIsNotEnabled()
        compose.onNodeWithTag("viewer_previous").assertIsDisplayed().performClick()
        compose.onNodeWithTag("image_viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "4.jpg"))
    }

    @Test fun `viewer semantic navigation reaches every image beyond first four`() {
        val refs = (1..7).map { AttachmentRef("$it", "$it.jpg", 10, "image/jpeg", "image") }
        compose.setContent { MaterialTheme { ImageViewer(refs, 0, ImageMemoryCache { throw java.io.IOException() }) {} } }
        repeat(6) {
            compose.onNodeWithTag("viewer_image").performCustomAction { actions ->
                assertTrue(actions.single { it.label == app.getString(R.string.mobile_viewer_next) }.action())
            }
        }
        compose.onNodeWithTag("image_viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "7.jpg"))
        compose.onNodeWithTag("viewer_image").performCustomAction { actions ->
            assertFalse(actions.any { it.label == app.getString(R.string.mobile_viewer_next) })
            actions.single { it.label == app.getString(R.string.mobile_viewer_previous) }.action()
        }
        compose.onNodeWithTag("image_viewer").assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "6.jpg"))
    }
}
