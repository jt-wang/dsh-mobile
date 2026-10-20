package dev.dshmobile.app.ui.chat

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun cameraUri(context: Context): Uri {
    val directory = File(context.cacheDir, "shared-media").apply { mkdirs() }
    return FileProvider.getUriForFile(context, "${context.packageName}.media", File.createTempFile("camera-", ".jpg", directory))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPicker(onDismiss: () -> Unit, count: Int, onPick: (List<Uri>, Boolean) -> Unit) {
    val context = LocalContext.current
    var cameraText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var cameraFailed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) {
        dev.dshmobile.app.data.notificationTrace("media_photos_result count=${it.size}")
        if (it.isNotEmpty()) onPick(it, false); onDismiss()
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onPick(it, false); onDismiss() }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        // Read the State at delivery, not a pre-permission composition's derived nullable value.
        val camera = cameraText?.let(Uri::parse)
        val bytes = camera?.let { runCatching { context.contentResolver.openAssetFileDescriptor(it, "r")?.use { file -> file.length } }.getOrNull() }
        dev.dshmobile.app.data.notificationTrace("media_camera_result success=$success uri_saved=${camera != null} bytes=${bytes ?: -1}")
        if (success) camera?.let { onPick(listOf(it), true); dev.dshmobile.app.data.notificationTrace("media_camera_on_pick") } else camera?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        onDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) try { cameraText = cameraUri(context).toString(); take.launch(Uri.parse(cameraText!!)) } catch (_: Exception) { cameraFailed = true }
        else cameraFailed = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            listOf(Triple(MobileIcons.Image, R.string.mobile_attach_photos, 0), Triple(MobileIcons.Camera, R.string.mobile_attach_camera, 1), Triple(MobileIcons.File, R.string.mobile_attach_files, 2)).forEach { (icon, label, action) ->
                TextButton(onClick = {
                    when (action) {
                        0 -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        1 -> if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(android.Manifest.permission.CAMERA)
                            else try { cameraText = cameraUri(context).toString(); take.launch(Uri.parse(cameraText!!)) } catch (_: Exception) { cameraFailed = true }
                        else -> files.launch(arrayOf("*/*"))
                    }
                }, enabled = count < 20, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.mobile_attach_limits, 20, 20), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (cameraFailed) PhoneError(stringResource(R.string.mobile_attach_camera_unavailable))
        }
    }
}

@Composable
internal fun AttachmentChips(items: List<UploadState>, imagesBlocked: Boolean, onRemove: (String) -> Unit, onRetry: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { state -> key(state.attachment.localId) {
            val item = state.attachment; val image = item.mime in PhoneRules.imageMimes
            val context = LocalContext.current
            val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
                if (image) value = withContext(Dispatchers.IO) { runCatching {
                    decodeBoundedImage({ context.contentResolver.openInputStream(Uri.parse(item.uri)) ?: error("unavailable") }, 256).bitmap
                }.getOrNull() }
            }
            val label = if (state.error) stringResource(R.string.mobile_attach_retry, item.name) else if (state.uploading) stringResource(R.string.mobile_attach_uploading, item.name, state.progress) else fileMeta(context, item.name, item.size)
            Box(Modifier.width(if (image) 64.dp else 208.dp).height(64.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest).border(if (state.error) 1.5.dp else 1.dp,
                    if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clickable(enabled = state.error, onClick = { onRetry(item.localId) }).semantics { contentDescription = label }.testTag("attachment_${item.localId}")) {
                if (image) {
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (imagesBlocked) 0.35f else 1f), contentScale = ContentScale.Crop) }
                        ?: Icon(MobileIcons.Image, null, Modifier.align(Alignment.Center))
                    if (state.uploading) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
                    if (state.error) Icon(MobileIcons.Refresh, null, Modifier.align(Alignment.Center))
                } else Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.error) MobileIcons.Refresh else MobileIcons.File, null, Modifier.size(28.dp)); Spacer(Modifier.width(8.dp))
                    Column { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(if (state.error) stringResource(R.string.mobile_attach_upload_failed) else fileMeta(context, item.name, item.size), style = MaterialTheme.typography.labelSmall) }
                }
                if (!image && state.uploading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp))
                IconButton(onClick = { onRemove(item.localId) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp).offset(x = 12.dp, y = (-12).dp).testTag("attachment_remove_${item.localId}")) {
                    Icon(MobileIcons.Close, stringResource(R.string.mobile_attach_remove, item.name), Modifier.size(16.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(20.dp)))
                }
            }
        } }
    }
}
internal fun fileMeta(context: Context, name: String, size: Long) = "${name.substringAfterLast('.', "FILE").uppercase()} · ${android.text.format.Formatter.formatShortFileSize(context, size)}"
