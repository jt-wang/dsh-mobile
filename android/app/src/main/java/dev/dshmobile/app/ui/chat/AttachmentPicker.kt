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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.sp
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.motionEnabled
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
    var cameraSettings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
    val permissionHistory = remember(context) { context.getSharedPreferences("attachment-permissions", Context.MODE_PRIVATE) }
    fun permanentlyDenied() = permissionHistory.getBoolean("camera-requested", false) && activity != null &&
        !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, android.Manifest.permission.CAMERA)
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
        else { cameraSettings = permanentlyDenied(); cameraFailed = !cameraSettings }
    }
    // Fully expanded and scrollable, so the permission card and its action are never cut off below the fold
    // at large font scales.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            listOf(Triple(MobileIcons.Image, R.string.mobile_attach_photos, 0), Triple(MobileIcons.Camera, R.string.mobile_attach_camera, 1), Triple(MobileIcons.File, R.string.mobile_attach_files, 2)).forEach { (icon, label, action) ->
                TextButton(onClick = {
                    when (action) {
                        0 -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        1 -> if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            if (permanentlyDenied()) cameraSettings = true
                            else { permissionHistory.edit().putBoolean("camera-requested", true).apply(); permission.launch(android.Manifest.permission.CAMERA) }
                        }
                            else try { cameraText = cameraUri(context).toString(); take.launch(Uri.parse(cameraText!!)) } catch (_: Exception) { cameraFailed = true }
                        else -> files.launch(arrayOf("*/*"))
                    }
                }, enabled = count < 20, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(icon, null, Modifier.size(24.dp)); Spacer(Modifier.width(16.dp)); Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.mobile_attach_limits, 20, 20), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val motion = motionEnabled
            // Permission is a recoverable state, not a failure: one amber card holds the explanation and
            // its way out, so the action clearly belongs to the message above it.
            AnimatedVisibility(cameraSettings, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "camera permission") {
                val accents = LocalMobileColors.current
                Column(Modifier.padding(top = Spacing.M).fillMaxWidth().background(accents.warningContainer, MaterialTheme.shapes.medium)
                    .padding(start = Spacing.L, end = Spacing.S, top = Spacing.M, bottom = Spacing.XS)) {
                    Row(Modifier.padding(end = Spacing.S), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                        Icon(MobileIcons.Camera, null, Modifier.padding(top = Spacing.XXS).size(IconSize.Row), tint = accents.warning)
                        Text(stringResource(R.string.mobile_attach_camera_permission), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        cameraSettings = false
                    }, modifier = Modifier.align(Alignment.End).heightIn(min = ControlSize.TouchTarget).testTag("attachment_camera_settings"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(stringResource(R.string.mobile_open_app_settings), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            // One message at a time: while the permission card offers a way out, an older "no camera app"
            // error from a previous attempt would only contradict it.
            if (cameraFailed && !cameraSettings) PhoneError(stringResource(R.string.mobile_attach_camera_unavailable))
        }
    }
}

@Composable
internal fun AttachmentChips(items: List<UploadState>, imagesBlocked: Boolean, onRemove: (String) -> Unit, onRetry: (String) -> Unit) {
    val motion = motionEnabled
    // Lazy row so a picked chip pops in and a removed one folds out while its neighbours slide over.
    val fade = remember(motion) { if (motion) Motion.tween<Float>(Motion.Medium, true) else null }
    val place = remember(motion) { if (motion) Motion.tween<androidx.compose.ui.unit.IntOffset>(Motion.Medium, true) else null }
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        items(items, key = { it.attachment.localId }, contentType = { if (it.attachment.mime in PhoneRules.imageMimes) "image" else "file" }) { state ->
            val item = state.attachment; val image = item.mime in PhoneRules.imageMimes
            val context = LocalContext.current
            val bitmap by produceState<android.graphics.Bitmap?>(null, item.uri) {
                if (image) value = withContext(Dispatchers.IO) { runCatching {
                    decodeBoundedImage({ context.contentResolver.openInputStream(Uri.parse(item.uri)) ?: error("unavailable") }, 256).bitmap
                }.getOrNull() }
            }
            val label = if (state.error) stringResource(R.string.mobile_attach_retry, item.name) else if (state.uploading && state.progress < 0) stringResource(R.string.mobile_attach_pending, item.name) else if (state.uploading) stringResource(R.string.mobile_attach_uploading, item.name, state.progress) else fileMeta(context, item.name, item.size)
            val shape = RoundedCornerShape(12.dp)
            val scrim = androidx.compose.ui.graphics.Color.Black
            Box(Modifier.animateItem(fadeInSpec = fade, placementSpec = place, fadeOutSpec = fade)
                .then(if (image) Modifier.size(64.dp) else Modifier.width(208.dp).heightIn(min = 64.dp)).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest).border(if (state.error) 1.5.dp else 1.dp,
                    if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(enabled = state.error, onClick = { onRetry(item.localId) }).semantics { contentDescription = label }.testTag("attachment_${item.localId}")) {
                if (image) {
                    // The thumbnail fades in once decoded instead of popping over the placeholder glyph.
                    androidx.compose.animation.Crossfade(bitmap, Modifier.fillMaxSize(), animationSpec = Motion.tween(Motion.Short, motion), label = "thumbnail") { decoded ->
                        if (decoded != null) Image(decoded.asImageBitmap(), null, Modifier.fillMaxSize().alpha(if (imagesBlocked) 0.35f else 1f), contentScale = ContentScale.Crop)
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(MobileIcons.Image, null, Modifier.size(IconSize.Action), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    // Upload state reads as a calm overlay: a light scrim, and for "queued" a caption band along
                    // the bottom edge (auto-sized so the whole word always fits the 64dp tile, at any font scale).
                    androidx.compose.animation.AnimatedVisibility(state.uploading || state.error, Modifier.matchParentSize(),
                        enter = Motion.panelIn(motion), exit = Motion.panelOut(motion), label = "upload overlay") {
                        Box(Modifier.fillMaxSize().background(scrim.copy(alpha = 0.32f)))
                    }
                    if (state.uploading && state.progress >= 0) Spinner(Modifier.align(Alignment.Center), size = 20.dp, color = androidx.compose.ui.graphics.Color.White)
                    if (state.uploading && state.progress < 0) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(scrim.copy(alpha = 0.62f))
                        .padding(horizontal = Spacing.XS, vertical = Spacing.XXS), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.text.BasicText(stringResource(R.string.mobile_attach_waiting),
                            style = MaterialTheme.typography.labelSmall.copy(color = androidx.compose.ui.graphics.Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = MaterialTheme.typography.labelSmall.fontSize, stepSize = 0.5.sp))
                    }
                    if (state.error) Icon(MobileIcons.Refresh, null, Modifier.align(Alignment.Center).size(IconSize.Row), tint = androidx.compose.ui.graphics.Color.White)
                } else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Spacing.M, end = 28.dp, top = Spacing.S, bottom = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.error) MobileIcons.Refresh else MobileIcons.File, null, Modifier.size(IconSize.Action), tint = if (state.error) MaterialTheme.colorScheme.error else LocalContentColor.current)
                    Spacer(Modifier.width(Spacing.M))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(if (state.error) stringResource(R.string.mobile_attach_upload_failed) else if (state.uploading && state.progress < 0) stringResource(R.string.mobile_attach_pending, item.name) else fileMeta(context, item.name, item.size),
                            style = MaterialTheme.typography.labelSmall, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (!image && state.uploading) {
                    if (state.progress < 0) LinearProgressIndicator(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp))
                    else LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp))
                }
                IconButton(onClick = { onRemove(item.localId) }, modifier = Modifier.align(Alignment.TopEnd).size(48.dp).offset(x = 12.dp, y = (-12).dp).testTag("attachment_remove_${item.localId}")) {
                    // A 20dp disc with a hairline keeps the close glyph legible over light and dark thumbnails.
                    Icon(MobileIcons.Close, stringResource(R.string.mobile_attach_remove, item.name), Modifier.size(20.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape).padding(4.dp))
                }
            }
        }
    }
}
internal fun fileMeta(context: Context, name: String, size: Long) = "${name.substringAfterLast('.', "FILE").uppercase()} · ${android.text.format.Formatter.formatShortFileSize(context, size)}"
