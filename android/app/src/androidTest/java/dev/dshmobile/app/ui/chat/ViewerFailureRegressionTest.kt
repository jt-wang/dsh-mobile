package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.model.AttachmentRef
import dev.dshmobile.app.ui.MobileTheme
import org.junit.Rule
import org.junit.Test

/** Adapted from the independent viewer probe; asserts the corrected user-visible behavior. */
class ViewerFailureRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failedNextImageClearsPreviousBitmapAndOffersRetry() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val data = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val secondRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val cache = ImageMemoryCache { id -> if (id == "second") { secondRequested.set(true); throw java.io.IOException("synthetic") }; data }
        val refs = listOf("first", "second").map { AttachmentRef(it, "$it.png", data.size.toLong(), "image/png", "image") }
        compose.setContent { MobileTheme { ImageViewer(refs, 0, cache) {} } }
        compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("first.png", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("image_viewer").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { secondRequested.get() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.mobile_media_retry)).assertExists()
        compose.onNode(hasContentDescription("first.png", substring = true)).assertDoesNotExist()
        compose.onNode(hasContentDescription("second.png", substring = true)).assertDoesNotExist()
    }
}
