package dev.dshmobile.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import dev.dshmobile.app.ui.components.StatusDot
import dev.dshmobile.app.ui.theme.LocalMobileColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import dev.dshmobile.app.R
import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.MobileState
import kotlinx.coroutines.launch

/** Wiring owns permission/platform work; content only renders immutable state and callbacks. */
@Composable internal fun NotificationSettingsPanel(state: MobileState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { NotificationStore(context) }
    var local by remember { mutableStateOf(NotificationLocal()) }
    var failed by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf(false) }
    LaunchedEffect(store) { try { ensureNotificationChannels(context); local = store.read(); local = syncNotificationPreferences(context) } catch (_: Exception) { failed = true } }
    LaunchedEffect(store) { store.changes.collect { runCatching { local = store.read() } } }
    fun change(enabled: Boolean, project: String? = null, chat: String? = null) {
        scope.launch {
            try {
                val host = EncryptedStateStore(context).read().host ?: return@launch
                local = store.update { old ->
                    val current = if (old.deviceId == host.deviceId) old else NotificationLocal(deviceId = host.deviceId, answerMode = old.answerMode, attentionMode = old.attentionMode, channelModesMigrated = old.channelModesMigrated)
                    val policy = current.settings
                    current.copy(enabled = if (project == null && chat == null) enabled else current.enabled,
                        settings = policy.copy(enabled = if (project == null && chat == null) enabled else policy.enabled,
                            projects = if (project != null) (policy.projects.filterNot { it.workspaceId == project } + ProjectNotificationSetting(project, enabled)).takeLast(100) else policy.projects,
                            chats = if (chat != null) (policy.chats.filterNot { it.sessionId == chat } + ChatNotificationSetting(chat, enabled)).takeLast(256) else policy.chats), dirty = true)
                }
                context.stopService(Intent(context, NotificationService::class.java))
                if (local.enabled) ContextCompat.startForegroundService(context, Intent(context, NotificationService::class.java))
                else context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                failed = false
                runCatching { local = syncNotificationPreferences(context) }
            } catch (_: Exception) { failed = true; runCatching { local = store.update { it.copy(enabled = false, monitoringStatus = "blocked") } } }
        }
    }
    fun changeMode(attention: Boolean, mode: AlertMode) {
        scope.launch {
            try {
                local = store.update { if (attention) it.copy(attentionMode = mode) else it.copy(answerMode = mode) }
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager.activeNotifications.filter { it.id == 2 && (it.tag?.endsWith(if (attention) ":attention" else ":answers") == true) }
                    .forEach { manager.cancel(it.tag, it.id) }
                failed = false
            } catch (_: Exception) { failed = true }
        }
    }
    fun openChannel(attention: Boolean) { scope.launch {
        ensureNotificationChannels(context)
        val event = NotificationEvent(1, "settings", 0, 0, 0, "settings", "settings", if (attention) "attention-needed" else "answer-finished", 0)
        val channel = notificationChannel(event, local) ?: if (attention) "attention_alert_v2" else "answers_alert_v2"
        runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)) }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        pendingPermission = false
        if (allowed) change(true) else failed = true
    }
    NotificationSettingsContent(state, local, failed,
        onEnabled = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = true; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else change(enabled)
        },
        onProject = { id, enabled -> change(enabled, project = id) }, onChat = { id, enabled -> change(enabled, chat = id) },
        onBattery = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
        onSystem = { runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } },
        onMode = ::changeMode, onChannel = ::openChannel, busy = pendingPermission)
}

@Composable internal fun NotificationSettingsContent(state: MobileState, local: NotificationLocal, failed: Boolean,
    onEnabled: (Boolean) -> Unit, onProject: (String, Boolean) -> Unit, onChat: (String, Boolean) -> Unit,
    onBattery: () -> Unit, onSystem: () -> Unit, onMode: (Boolean, AlertMode) -> Unit,
    onChannel: (Boolean) -> Unit, busy: Boolean) {
    // Status first (why the switch may be unavailable), then the switch, then two explanations in one quiet style.
    if (state.capabilities?.notifications != true) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }), contentAlignment = Alignment.Center) {
            StatusDot(LocalMobileColors.current.warningIndicator)
        }
        Text(stringResource(R.string.notif_unsupported), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
    ToggleLine(stringResource(R.string.notif_title), local.enabled, onEnabled, !busy && (local.enabled || state.capabilities?.notifications == true))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.notif_rationale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.notif_restart), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (local.monitoringStatus) {
        "degraded" -> Text(stringResource(R.string.notif_degraded), color = MaterialTheme.colorScheme.error)
        "initializing" -> if (local.enabled) Text(stringResource(R.string.notif_initializing), style = MaterialTheme.typography.bodyMedium)
        "offline" -> Text(stringResource(R.string.notif_offline), style = MaterialTheme.typography.bodyMedium)
        "blocked" -> Text(stringResource(R.string.notif_blocked), color = MaterialTheme.colorScheme.error)
    }
    if (failed) Text(stringResource(R.string.notif_blocked), color = MaterialTheme.colorScheme.error)
    if (local.dirty) Text(stringResource(R.string.notif_unsynced), style = MaterialTheme.typography.bodySmall)
    AlertModeLine(stringResource(R.string.notif_answers_channel), local.answerMode, !busy) { onMode(false, it) }
    AlertModeLine(stringResource(R.string.notif_attention_channel), local.attentionMode, !busy) { onMode(true, it) }
    // Links to system screens are navigation, not actions: one full-width list with chevrons and hairlines,
    // so long labels wrap inside a row instead of producing pills of different widths.
    Column {
        LinkRowDivider()
        SettingsLinkRow(stringResource(R.string.notif_answer_system), { onChannel(false) })
        LinkRowDivider()
        SettingsLinkRow(stringResource(R.string.notif_attention_system), { onChannel(true) })
        LinkRowDivider()
        SettingsLinkRow(stringResource(R.string.notif_system), onSystem)
        LinkRowDivider()
        SettingsLinkRow(stringResource(R.string.notif_battery), onBattery)
        LinkRowDivider()
    }
    Text(stringResource(R.string.notif_oneplus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    var filters by remember { mutableStateOf(false) }
    SettingsLinkRow(stringResource(R.string.notif_filters), { filters = !filters }, trailing = dev.dshmobile.app.ui.MobileIcons.ChevronDown,
        trailingRotation = if (filters) 180f else 0f)
    if (filters) {
        Text(stringResource(R.string.notif_projects), style = MaterialTheme.typography.titleSmall)
        state.workspaces.forEach { workspace ->
            ToggleLine(workspace.name, local.settings.projects.find { it.workspaceId == workspace.id }?.enabled ?: true,
                { onProject(workspace.id, it) }, !busy)
        }
        Text(stringResource(R.string.notif_chats), style = MaterialTheme.typography.titleSmall)
        state.sessions.take(256).forEach { chat ->
            ToggleLine(chat.title, local.settings.chats.find { it.sessionId == chat.id }?.enabled
                ?: local.settings.projects.find { it.workspaceId == chat.workspaceId }?.enabled ?: true,
                { onChat(chat.id, it) }, !busy)
        }
    }
}
@Composable private fun AlertModeLine(label: String, mode: AlertMode, enabled: Boolean, change: (AlertMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(when (mode) { AlertMode.SOUND -> R.string.notif_mode_sound; AlertMode.SILENT -> R.string.notif_mode_silent; AlertMode.OFF -> R.string.notif_mode_off })
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) { Text(current) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                AlertMode.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(stringResource(when (choice) { AlertMode.SOUND -> R.string.notif_mode_sound; AlertMode.SILENT -> R.string.notif_mode_silent; AlertMode.OFF -> R.string.notif_mode_off })) },
                        onClick = { expanded = false; change(choice) })
                }
            }
        }
    }
}
@Composable private fun ToggleLine(label: String, checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        // Unavailable reads as unavailable: muted label and a hollow, faint switch rather than a normal "off".
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
        Switch(checked, change, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = colors.onSurface,
            checkedThumbColor = colors.surface, uncheckedBorderColor = colors.outline,
            disabledUncheckedTrackColor = Color.Transparent, disabledUncheckedBorderColor = colors.outlineVariant,
            disabledUncheckedThumbColor = colors.outlineVariant), modifier = Modifier.padding(start = 8.dp)
            .then(Modifier.semanticsLabel(label)))
    }
}
private fun Modifier.semanticsLabel(label: String): Modifier = this.then(Modifier.semantics { contentDescription = label })
