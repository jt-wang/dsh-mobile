package dev.dshmobile.app.ui.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.bleed
import dev.dshmobile.app.ui.components.canCreate
import dev.dshmobile.app.ui.components.edgeFade
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import dev.dshmobile.app.ui.home.projectSummaries
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Spacing

/**
 * One sheet for the whole start: project, optional preset, optional task text.
 * With text the button reads "Send" (create, then send once); empty text keeps the old "Create chat".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewChatSheet(state: MobileState, preferredProject: String?, onDismiss: () -> Unit, onCreate: (String, String?) -> Unit,
    task: String = "", onTask: (String) -> Unit = {}) {
    val projects = remember(state.workspaces, state.sessions) { projectSummaries(state.workspaces, state.sessions) }
    val initial = preferredProject?.takeIf { id -> projects.any { it.workspace.id == id && it.workspace.canExecute } }
        ?: projects.firstOrNull { it.workspace.canExecute }?.workspace?.id
    var workspace by rememberSaveable { mutableStateOf(initial) }
    var preset by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(projects, search) { projects.filter { it.workspace.name.contains(search.trim(), ignoreCase = true) } }
    val accents = LocalMobileColors.current
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * 0.9f }
    val tooLong = task.toByteArray(Charsets.UTF_8).size > 32_768
    // A chip may take the full row between gutters, so the selected preset name is not cut at large text.
    val chipMax = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } - Spacing.Gutter * 2
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow, dragHandle = { BottomSheetDefaults.DragHandle(color = colors.outline) }) {
        // One scroll for the whole form (no nested list scroll): rows never stop half-visible above the caption,
        // and the task field and Send stay reachable with the IME up, in landscape or at 2x text.
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Gutter).padding(bottom = 16.dp).testTag("new_chat_sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.mobile_new_chat), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.mobile_projects), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = muted)
            if (projects.size > 8) TextField(value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.mobile_search_projects)) }, leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(IconSize.Row)) },
                shape = CircleShape, colors = TextFieldDefaults.colors(unfocusedContainerColor = colors.surfaceContainerHigh, focusedContainerColor = colors.surfaceContainerHigh,
                    unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().testTag("workspace_search"))
            Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectableGroup().testTag("workspace_picker")) {
                if (filtered.isEmpty()) Text(stringResource(R.string.mobile_projects_unavailable), Modifier.padding(vertical = 16.dp), color = muted)
                filtered.forEach { project -> key(project.workspace.id) {
                    val item = project.workspace
                    val selected = workspace == item.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.small)
                        .background(if (selected) colors.surfaceContainerHigh else Color.Transparent)
                        .selectable(selected = selected, enabled = item.canExecute, role = Role.RadioButton, onClick = { workspace = item.id })
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("workspace_${item.id}"),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(MobileIcons.Folder, null, Modifier.size(IconSize.Row), tint = if (item.canExecute) colors.onSurface else muted)
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (item.canExecute) colors.onSurface else muted)
                        Text(if (item.canExecute) pluralStringResource(R.plurals.mobile_project_count, project.chatCount, project.chatCount) else stringResource(R.string.mobile_read_only),
                            style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
                        Box(Modifier.size(IconSize.Row), contentAlignment = Alignment.Center) {
                            if (selected) Icon(MobileIcons.Check, null, Modifier.size(IconSize.Row), tint = colors.onSurface)
                        }
                    }
                } }
            }
            if (projects.none { it.workspace.canExecute }) Text(stringResource(R.string.mobile_no_executable_workspace), style = MaterialTheme.typography.bodySmall, color = muted)
            Text(stringResource(R.string.mobile_workspace_scope), style = MaterialTheme.typography.bodySmall, color = muted)
            if (state.presets.isNotEmpty()) {
                Text(stringResource(R.string.mobile_presets), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = muted)
                // Full sheet width: chips scroll out to the screen edge (with a fade), first chip starts on the gutter.
                val presetRow = rememberLazyListState()
                LazyRow(Modifier.bleed(Spacing.Gutter).edgeFade(presetRow).testTag("preset_picker"), state = presetRow,
                    contentPadding = PaddingValues(horizontal = Spacing.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item(key = LazyItemKeys.DEFAULT_PRESET) {
                        PresetChip(stringResource(R.string.mobile_host_default), preset == null, chipMax, Modifier.testTag("preset_default")) { preset = null }
                    }
                    items(state.presets, key = { LazyItemKeys.preset(it.id) }) { item ->
                        PresetChip(item.name, preset == item.id, chipMax, Modifier.testTag("preset_${item.id}")) { preset = item.id }
                    }
                }
            }
            val hint = stringResource(R.string.mobile_task_hint)
            BasicTextField(value = task, onValueChange = onTask, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface), minLines = 3, maxLines = 8,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().semantics { contentDescription = hint }.testTag("new_task_text"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(20.dp))
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
                        if (task.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = muted)
                        inner()
                    }
                })
            if (tooLong) Text(stringResource(R.string.mobile_error_invalid_text), style = MaterialTheme.typography.bodySmall, color = colors.error)
            val withTask = task.isNotBlank()
            Button(onClick = { workspace?.let { onCreate(it, preset) } }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("create_chat"),
                enabled = canCreate(state) && !tooLong && state.workspaces.any { it.id == workspace && it.canExecute } && (preset == null || state.presets.any { it.id == preset }),
                shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = accents.action, contentColor = accents.onAction,
                    disabledContainerColor = colors.surfaceContainerHighest, disabledContentColor = muted)) {
                Text(stringResource(if (withTask) R.string.mobile_send_task else R.string.mobile_create), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, maxWidth: androidx.compose.ui.unit.Dp, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.widthIn(max = maxWidth).heightIn(min = 48.dp).padding(vertical = 4.dp).clip(CircleShape)
        .background(if (selected) colors.onSurface else Color.Transparent)
        .border(1.dp, if (selected) colors.onSurface else colors.outlineVariant, CircleShape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (selected) Icon(MobileIcons.Check, null, Modifier.size(IconSize.Meta), tint = colors.surface)
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.surface else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
