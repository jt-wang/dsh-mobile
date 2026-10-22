package dev.dshmobile.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dev.dshmobile.app.model.DraftAttachment
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object ImageSizing {
    const val MAX_BITMAP_BYTES = 16L * 1024 * 1024
    fun sample(width: Int, height: Int, maxSide: Int): Int {
        require(width > 0 && height > 0 && width <= 100_000 && height <= 100_000)
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > maxSide || (height.toLong() + sample - 1) / sample > maxSide ||
            ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample) * 4 > MAX_BITMAP_BYTES) sample *= 2
        return sample
    }
}
internal fun exifTransform(orientation: Int) = android.graphics.Matrix().apply {
    when (orientation) {
        2 -> setScale(-1f, 1f)
        3 -> setRotate(180f)
        4 -> setScale(1f, -1f)
        5 -> { setRotate(90f); postScale(-1f, 1f) }
        6 -> setRotate(90f)
        7 -> { setRotate(90f); postScale(1f, -1f) }
        8 -> setRotate(270f)
    }
}
internal data class DecodedImage(val bitmap: Bitmap, val width: Int, val height: Int)
internal fun decodeBoundedImage(open: () -> InputStream, maxSide: Int): DecodedImage {
    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, dimensions) }
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageSizing.sample(dimensions.outWidth, dimensions.outHeight, maxSide)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: error("image_unavailable")
    if (bitmap.allocationByteCount > ImageSizing.MAX_BITMAP_BYTES) { bitmap.recycle(); error("image_memory") }
    // Read metadata on its own stream: both bounds and bitmap decoding consume theirs.
    val orientation = runCatching { open().use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
    val matrix = exifTransform(orientation)
    val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    val swap = orientation in 5..8
    return DecodedImage(oriented, if (swap) dimensions.outHeight else dimensions.outWidth, if (swap) dimensions.outWidth else dimensions.outHeight)
}

/** Cache files are never external URI paths. Shares expire at the provider;
 * camera sources expire only through draft-aware cleanup. */
internal object TemporaryMedia {
    const val SHARE_TTL_MS = 10 * 60 * 1000L
    const val CAMERA_TTL_MS = 24 * 60 * 60 * 1000L
    private val expiry = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "media-expiry").apply { isDaemon = true } }
    fun requireReadable(file: File, now: Long = System.currentTimeMillis()) {
        if (file.name.startsWith("share-") && now - file.lastModified() >= SHARE_TTL_MS) throw java.io.FileNotFoundException("Expired media")
    }
    fun directory(context: Context) = File(context.cacheDir, "shared-media").apply { mkdirs() }
    fun delete(context: Context, file: File) {
        if (file.parentFile?.canonicalFile != directory(context).canonicalFile) return
        runCatching { context.revokeUriPermission(FileProvider.getUriForFile(context, "${context.packageName}.media", file), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        file.delete()
    }
    fun cleanup(context: Context, drafts: Collection<DraftAttachment>, now: Long = System.currentTimeMillis()) {
        val active = drafts.filter { it.temporary }.map { Uri.parse(it.uri).lastPathSegment }.toSet()
        directory(context).listFiles()?.forEach { file ->
            if (file.name.startsWith("share-") && now - file.lastModified() >= SHARE_TTL_MS ||
                file.name.startsWith("camera-") && file.name !in active && now - file.lastModified() >= CAMERA_TTL_MS) delete(context, file)
        }
    }
    fun cleanupShares(context: Context, now: Long = System.currentTimeMillis()) {
        directory(context).listFiles()?.filter { it.name.startsWith("share-") && now - it.lastModified() >= SHARE_TTL_MS }?.forEach { delete(context, it) }
    }
    fun expireShare(context: Context, file: File) {
        expiry.schedule({ delete(context.applicationContext, file) }, SHARE_TTL_MS, TimeUnit.MILLISECONDS)
    }
}

class ExpiringMediaProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        val name = uri.lastPathSegment ?: throw java.io.FileNotFoundException()
        val file = File(TemporaryMedia.directory(requireNotNull(context)), name)
        // Only draft-aware cleanup may expire camera sources. A saved draft (or a
        // retained uncertain send) can outlive 24 h and still need a fresh upload.
        TemporaryMedia.requireReadable(file)
        return super.openFile(uri, mode) ?: throw java.io.FileNotFoundException()
    }
}
