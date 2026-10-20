package dev.dshmobile.app.ui.chat

import android.Manifest
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.R
import dev.dshmobile.app.ui.MobileTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PickerRestorationRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun documentExportUsesSafAndSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var request = 0
        var intent: Intent? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                request = requestCode; intent = contract.createIntent(context, input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MobileTheme {
            val save = rememberDocumentSaver(androidx.compose.material3.SnackbarHostState())
            androidx.compose.material3.Button(onClick = { save(dev.dshmobile.app.model.AttachmentRef("id", "export.txt", 9, "text/plain", "file"), "synthetic".toByteArray()) }) { androidx.compose.material3.Text("Export") }
        } } }
        compose.onNodeWithText("Export").performClick()
        compose.waitUntil(5000) { intent != null }
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent!!.action)
        assertEquals("export.txt", intent!!.getStringExtra(Intent.EXTRA_TITLE))
        restoration.emulateSavedInstanceStateRestore()
        val output = java.io.File(TemporaryMedia.directory(context), "share-regression-saf.txt").apply { writeText("") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.media", output)
        try {
            compose.runOnIdle { registry.dispatchResult(request, uri) }
            compose.waitUntil(5000) { output.readText() == "synthetic" }
        } finally { output.delete() }
    }
    @Test fun sharingPreservesRecentExportsAndCleansExpiredOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = TemporaryMedia.directory(context)
        val recent = java.io.File(dir, "share-regression-recent.txt").apply { writeText("synthetic") }
        val expired = java.io.File(dir, "share-regression-expired.txt").apply { writeText("synthetic"); setLastModified(System.currentTimeMillis() - TemporaryMedia.SHARE_TTL_MS - 1) }
        try {
            TemporaryMedia.cleanupShares(context)
            assertTrue(recent.exists()); assertFalse(expired.exists())
        } finally { recent.delete(); expired.delete() }
    }
    @Test fun cameraPermissionAndImmediateResultReadLatestUri() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val noCameraPermission = object : android.content.ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.CAMERA) android.content.pm.PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
        }
        var permissionRequest = 0
        var launched: String? = null
        var received: String? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (contract is androidx.activity.result.contract.ActivityResultContracts.RequestPermission) permissionRequest = requestCode
                else { launched = input.toString(); dispatchResult(requestCode, true) }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, androidx.compose.ui.platform.LocalContext provides noCameraPermission) { MobileTheme {
            AttachmentPicker({}, 0) { uris, temporary -> assertTrue(temporary); received = uris.single().toString() }
        } } }
        compose.onNodeWithText(context.getString(R.string.mobile_attach_camera)).performClick()
        compose.runOnIdle { assertNotEquals(0, permissionRequest); registry.dispatchResult(permissionRequest, true) }
        compose.runOnIdle { assertNotNull(launched); assertEquals(launched, received) }
        launched?.let { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
    }
    @Test fun cameraResultAfterRecreationKeepsOriginalUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}").close()
        var request = 0
        var launched: String? = null
        var received: String? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                request = requestCode
                launched = input.toString()
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { MobileTheme {
            AttachmentPicker({}, 0) { uris, temporary -> assertTrue(temporary); received = uris.single().toString() }
        } } }
        compose.onNodeWithText(context.getString(R.string.mobile_attach_camera)).performClick()
        compose.waitUntil(5000) { launched != null }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(request, true) }
        compose.runOnIdle { assertEquals(launched, received) }
        launched?.let { TemporaryMedia.delete(context, java.io.File(TemporaryMedia.directory(context), android.net.Uri.parse(it).lastPathSegment!!)) }
    }
}
