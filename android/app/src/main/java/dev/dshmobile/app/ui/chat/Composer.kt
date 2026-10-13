package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.LocalMobileColors

@Composable
internal fun Composer(state: MobileState, model: MobileViewModel, onCancel: () -> Unit) {
    val haptic = rememberActionHaptic()
    val draftSize = remember(model.editorDraft) { model.editorDraft.toByteArray(Charsets.UTF_8).size }
    val sendEnabled = MobileReducer.canSend(state, model.editorDraft) && !model.interactionBlocked
    val running = state.snapshot?.activity == "running"
    val inputEnabled = canEditDraft(state, model.interactionBlocked)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PendingDelivery(state, model)
            }
            if (state.snapshot != null) {
                if (draftSize > 32_768) ErrorText("invalid_text", Modifier.padding(horizontal = 8.dp))
                PillField(value = model.editorDraft, onValueChange = model::updateDraft,
                    placeholder = stringResource(if (running) R.string.mobile_message_queued_hint else R.string.mobile_message),
                    enabled = inputEnabled, modifier = Modifier.testTag("message_input")) {
                    // Secondary Stop retains ChatScreen's existing confirmation callback.
                    if (running) IconButton(onClick = onCancel, enabled = canCancel(state) && !model.interactionBlocked,
                        modifier = Modifier.size(48.dp).testTag("stop_run")) {
                        Box(Modifier.size(36.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(MobileIcons.StopFilled, stringResource(R.string.mobile_cancel_run), Modifier.size(18.dp))
                        }
                    }
                    RoundActionButton(MobileIcons.Send, stringResource(R.string.mobile_send), sendEnabled, Modifier.testTag("send_message")) { model.sendMessage(haptic) }
                }
                if (state.connection != ConnectionState.ONLINE && executable(state)) Text(stringResource(R.string.mobile_offline_draft),
                    Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Rounded field with trailing actions inside the same pill, as in mobile chat clients. */
@Composable
internal fun PillField(value: String, onValueChange: (String) -> Unit, placeholder: String, enabled: Boolean, modifier: Modifier = Modifier,
    minLines: Int = 1, maxLines: Int = 6, actions: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .background(colors.surfaceContainer, RoundedCornerShape(28.dp))
        .border(1.dp, colors.outlineVariant, RoundedCornerShape(28.dp))
        .padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom) {
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, textStyle = text,
            cursorBrush = SolidColor(colors.onSurface), minLines = minLines, maxLines = maxLines,
            modifier = modifier.weight(1f).padding(top = 12.dp, bottom = 12.dp, end = 8.dp).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                    inner()
                }
            })
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Primary round action: the inverse of the ground, the app's only filled control. */
@Composable
internal fun RoundActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accents = LocalMobileColors.current
    FilledIconButton(onClick = onClick, enabled = enabled, shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accents.action, contentColor = accents.onAction,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(22.dp))
    }
}
