package dev.dshmobile.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.safeErrorResource
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

internal fun canCreate(state: MobileState) = state.connection == ConnectionState.ONLINE && state.capabilities?.sessions == true && !state.busy && state.pending == null && state.workspaces.any { it.canExecute }
internal fun executable(state: MobileState) = state.snapshot?.let { snapshot -> snapshot.session.canExecute && state.workspaces.any { it.id == snapshot.session.workspaceId && it.canExecute } } == true
// Staleness gates host actions, not local drafting; navigation/admission still pins the editor's chat.
internal fun canEditDraft(state: MobileState, interactionBlocked: Boolean = false) = executable(state) && (state.snapshotStale || state.capabilities?.textPrompt == true) && (state.snapshotStale || (!state.busy && !interactionBlocked)) && state.pending == null
internal fun canCancel(state: MobileState) = state.connection == ConnectionState.ONLINE && state.capabilities?.cancel == true && executable(state) && !state.busy && !state.snapshotStale && state.pending == null && state.snapshot?.activity == "running" && state.snapshot.session.running
@Composable
internal fun formattedTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(time))
@Composable
internal fun shortTime(time: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT,
    androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(time))

@Composable
internal fun workspaceName(state: MobileState, id: String) = state.workspaces.find { it.id == id }?.name ?: stringResource(R.string.mobile_workspace_unknown)

@Composable
internal fun ErrorText(key: String, modifier: Modifier = Modifier) {
    Text(stringResource(safeErrorResource(key)), modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun Notice(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.widthIn(max = 440.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(MobileIcons.Chat, null, Modifier.size(IconSize.Action), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/**
 * Synthetic-data marker: a small framed label, so it never reads as a section heading or as content.
 * It is a provenance disclosure, so it wraps at large font scales instead of cutting "no DSH" off.
 */
@Composable
internal fun DemoBadge(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.mobile_demo_tag), modifier
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
        .padding(horizontal = Spacing.S, vertical = Spacing.XXS).testTag("demo_label"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
