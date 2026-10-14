package dev.dshmobile.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.*

/** Flat sections separated by hairlines, no cards: one column of facts and a few explicit actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(state: MobileState, model: MobileViewModel, onBack: () -> Unit) {
    var forget by remember { mutableStateOf(false) }
    val theme = LocalThemeSetting.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        CenterAlignedTopAppBar(title = { Text(stringResource(R.string.mobile_settings_connection), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Icon(MobileIcons.ChevronLeft, stringResource(R.string.mobile_back)) } },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(bottom = 24.dp).testTag("settings_screen")) {
            // Computer: identity first, as on a device page.
            // Identity row sized like a list item, not a headline: the screen title stays the largest text.
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.Gutter, vertical = Spacing.L), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Box(Modifier.size(ControlSize.RoundAction).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(MobileIcons.Computer, null, Modifier.size(IconSize.Row))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
                    SelectionContainer { Text(state.hostName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(connectionColor(state.connection))
                        Text(connectionLabel(state.connection), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
            if (state.demo) Text(stringResource(R.string.mobile_demo_notice), Modifier.padding(horizontal = Spacing.Gutter), style = MaterialTheme.typography.bodySmall, color = muted)

            Section(stringResource(R.string.mobile_connection_section)) {
                Text(state.lastSyncedAt?.let { stringResource(R.string.mobile_last_sync, formattedTime(it)) } ?: stringResource(R.string.mobile_never_synced),
                    style = MaterialTheme.typography.bodyMedium, color = muted)
                if (state.remoteMode) {
                    Text(stringResource(R.string.mobile_remote_mode), Modifier.testTag("settings_remote_mode"), style = MaterialTheme.typography.titleSmall)
                    state.relayHost?.let { host -> SelectionContainer { Text(stringResource(R.string.mobile_relay_host, host), style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily, color = muted) } }
                    Text(stringResource(R.string.mobile_remote_explanation), style = MaterialTheme.typography.bodyMedium, color = muted)
                    Text(stringResource(R.string.mobile_pc_online_required), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                state.error?.let { ErrorText(it) }
                if (state.connection == ConnectionState.REVOKED) Text(stringResource(R.string.mobile_revoked_help), style = MaterialTheme.typography.bodyMedium)
                if (state.connection == ConnectionState.INCOMPATIBLE) Text(stringResource(R.string.mobile_incompatible_help), style = MaterialTheme.typography.bodyMedium)
                if (state.connection !in listOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) OutlineAction(stringResource(R.string.mobile_refresh_connection), !state.busy, model::refresh)
            }

            Section(stringResource(R.string.mobile_theme)) {
                // Segmented control: three equal options, current one filled with the ink colour.
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(ThemePreference.SYSTEM, ThemePreference.LIGHT, ThemePreference.DARK).forEach { option ->
                        val selected = theme.value == option
                        Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { theme.update(option) })
                            .testTag("theme_${option.name.lowercase()}"), contentAlignment = Alignment.Center) {
                            Text(stringResource(when (option) { ThemePreference.DARK -> R.string.mobile_theme_dark; ThemePreference.LIGHT -> R.string.mobile_theme_light; ThemePreference.SYSTEM -> R.string.mobile_theme_system }),
                                style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onSurface else muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }

            Section(stringResource(R.string.mobile_device_section)) {
                NotificationSettingsPanel(state)
                Text(stringResource(R.string.mobile_phone_limits), style = MaterialTheme.typography.bodySmall, color = muted)
            }

            Section(null) {
                Text(stringResource(R.string.mobile_forget_help), style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton(onClick = { forget = true }, enabled = !state.busy, modifier = Modifier.offset(x = (-12).dp).heightIn(min = 48.dp).testTag("forget_connection"),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.mobile_forget), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    if (forget) AlertDialog(onDismissRequest = { forget = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.mobile_forget_title)) },
        text = { Text(stringResource(R.string.mobile_forget_confirmation)) },
        confirmButton = { TextButton(onClick = { forget = false; model.forget() }, enabled = !state.busy, modifier = Modifier.testTag("forget_connection_confirm"),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.mobile_forget_confirm)) } },
        dismissButton = { TextButton(onClick = { forget = false }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.mobile_back)) } })
}

@Composable
private fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        content()
    }
}

/**
 * Full-width navigation row to a system screen: label left, chevron right, 56dp. It bleeds through the section
 * gutter so the ripple spans the screen while the text stays on the gutter. Use [LinkRowDivider] between rows.
 */
@Composable
internal fun SettingsLinkRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: ImageVector = MobileIcons.ChevronRight,
    trailingRotation: Float = 0f) {
    Row(modifier.bleed(Spacing.Gutter).fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = Spacing.Gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Text(label, Modifier.weight(1f).padding(vertical = Spacing.M), style = MaterialTheme.typography.bodyLarge)
        Icon(trailing, null, Modifier.size(IconSize.Row).rotate(trailingRotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LinkRowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun OutlineAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = modifier.heightIn(min = 48.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
