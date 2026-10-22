package dev.dshmobile.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.model.DraftAttachment
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Platform JNI EXIF/bitmap and FileProvider checks; complements JVM policy/matrix tests. */
class Media081PlatformTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savedCameraOlderThan24HoursOpensThroughProvider() {
        val file = File(TemporaryMedia.directory(context), "camera-regression.jpg").apply {
            writeText("photo"); setLastModified(System.currentTimeMillis() - TemporaryMedia.CAMERA_TTL_MS - 1000)
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.media", file)
            TemporaryMedia.cleanup(context, listOf(DraftAttachment("saved", uri.toString(), "photo.jpg", 5, "image/jpeg", temporary = true)))
            assertEquals("photo", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        } finally { TemporaryMedia.delete(context, file) }
    }

    @Test fun thumbnailsAndViewerDecodeAllEightExifOrientations() {
        val file = File(context.cacheDir, "orientation-regression.jpg")
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 80) original.setPixel(x, y, if (x < 40) Color.RED else Color.BLUE)
        file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        try {
            val redCorner = setOf(1, 4, 5, 6)
            for (orientation in 1..8) {
                ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "$orientation"); saveAttributes() }
                for (max in listOf(32, 4096)) {
                    val decoded = decodeBoundedImage({ file.inputStream() }, max)
                    assertEquals(if (orientation >= 5) 40 else 80, decoded.width)
                    assertEquals(if (orientation >= 5) 80 else 40, decoded.height)
                    val pixel = decoded.bitmap.getPixel(2, 2)
                    assertTrue("orientation $orientation", if (orientation in redCorner) Color.red(pixel) > 200 else Color.blue(pixel) > 200)
                    decoded.bitmap.recycle()
                }
            }
        } finally { original.recycle(); file.delete() }
    }
}
