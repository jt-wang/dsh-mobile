package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.shortTime
import dev.dshmobile.app.ui.theme.MonoFamily
import dev.dshmobile.app.ui.theme.tabular
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.ChatMessage

/** Disclosure is local; full original text is always available, never only an ellipsized preview. */
@Composable
internal fun AgentActivityGroup(group: ChatItem.Activity, onCopy: (String) -> Unit = {}) {
    var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChatMessage?>(null) }
    val label = stringResource(R.string.mobile_agent_activity_count, group.messages.size)
    val disclosure = stringResource(if (expanded) R.string.mobile_activity_expanded else R.string.mobile_activity_collapsed)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "activity chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { expanded = !expanded }
            .heightIn(min = 48.dp).padding(end = 8.dp).testTag("agent_activity_expander").semantics(mergeDescendants = true) { stateDescription = disclosure },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(MobileIcons.Terminal, null, Modifier.size(16.dp), tint = muted)
            Text(label, style = MaterialTheme.typography.labelLarge, color = muted)
            Icon(MobileIcons.ChevronRight, null, Modifier.size(16.dp).rotate(rotation), tint = muted)
        }
        if (expanded) {
            val rail = MaterialTheme.colorScheme.outlineVariant
            // A hairline rail keeps expanded service items visually separate from the conversation.
            Column(Modifier.fillMaxWidth().padding(start = 7.dp)
                .drawBehind { drawRect(rail, size = Size(1.dp.toPx(), size.height)) }
                .padding(start = 16.dp).testTag("agent_activity_details")) {
                group.messages.forEach { message ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { selected = message }
                        .heightIn(min = 44.dp).testTag("agent_activity_item:${message.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (message.kind == "context") stringResource(R.string.mobile_context_updated)
                            else message.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim(),
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall.tabular(), color = muted)
                    }
                }
            }
        }
    }
    selected?.let { message ->
        val full = message.text + message.serviceText.orEmpty()
        AlertDialog(onDismissRequest = { selected = null }, modifier = Modifier.testTag("agent_activity_dialog"), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(R.string.mobile_activity_full_text)) },
            text = {
                SelectionContainer { Text(full, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).testTag("agent_activity_full_text"),
                    style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily) }
            },
            confirmButton = { TextButton(onClick = { onCopy(full) }, modifier = Modifier.testTag("agent_activity_copy")) { Text(stringResource(R.string.mobile_copy_message)) } },
            dismissButton = { TextButton(onClick = { selected = null }, modifier = Modifier.testTag("agent_activity_close")) { Text(stringResource(R.string.mobile_activity_close)) } })
    }
}
