package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.ChatMessage
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.shortTime
import dev.dshmobile.app.ui.markdown.MarkdownContent
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.tabular

@Composable
internal fun MessageItem(message: ChatMessage, onCopy: (String) -> Unit, searchQuery: String = "", selectedMatch: SearchMatch? = null) {
    val user = message.role == "user"
    var menu by remember(message.id) { mutableStateOf(false) }
    var selecting by remember(message.id) { mutableStateOf(false) }
    val labels = messageActions.associateWith { action -> stringResource(when (action) {
        MessageAction.Copy -> R.string.mobile_copy_message
        MessageAction.CopyAsText -> R.string.mobile_copy_as_text
        MessageAction.Select -> R.string.mobile_select_message
    }) }
    val menuLabel = stringResource(R.string.mobile_message_actions)
    fun perform(action: MessageAction) {
        menu = false
        when (action) {
            MessageAction.Copy -> onCopy(message.text)
            MessageAction.CopyAsText -> onCopy(messagePlainText(message.text))
            MessageAction.Select -> selecting = true
        }
    }
    Column(Modifier.fillMaxWidth().testTag("message:${message.id}")
        .combinedClickable(onClick = {}, onLongClickLabel = menuLabel, onLongClick = { menu = true }, hapticFeedbackEnabled = false)
        .semantics { customActions = messageActions.map { action -> CustomAccessibilityAction(labels.getValue(action)) { perform(action); true } } },
        horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val content: @Composable () -> Unit = {
            if (searchQuery.isNotBlank()) SearchableMessageText(message, searchQuery, selectedMatch)
            else if (user) Text(message.text, style = MaterialTheme.typography.bodyLarge)
            else MarkdownContent(message.text, onCopy, selectable = false)
        }
        if (user) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(0.86f).wrapContentWidth(Alignment.End)) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) { content() }
        } else {
            // System notices keep a label; assistant output reads as plain document text.
            if (message.role != "assistant") Text(stringResource(R.string.mobile_role_system),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        // Compact meta row: 24dp tall, copy glyph aligned to the text edge. The copy target still answers
        // touches across 48dp (Compose extends small pointer targets to the minimum touch size).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
            modifier = Modifier.heightIn(min = ControlSize.MetaRow).offset(x = if (user) Spacing.XS else -Spacing.XS)) {
            if (!user) Box {
                CopyButton(message, labels.getValue(MessageAction.Copy), onCopy)
                MessageMenu(menu, { menu = false }, labels, ::perform)
            }
            Text(shortTime(message.createdAt), style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (user) Box {
                CopyButton(message, labels.getValue(MessageAction.Copy), onCopy)
                MessageMenu(menu, { menu = false }, labels, ::perform)
            }
        }
        if (message.provisional) Text(stringResource(R.string.mobile_provisional), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (selecting) androidx.compose.ui.window.Dialog(onDismissRequest = { selecting = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                TextButton(onClick = { selecting = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(MobileIcons.Back, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mobile_back))
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(labels.getValue(MessageAction.Select), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.mobile_selection_hint), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(message.text, Modifier.testTag("select_message_text"), style = MaterialTheme.typography.bodyLarge) }
                    OutlinedButton(onClick = { onCopy(message.text) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(labels.getValue(MessageAction.Copy)) }
                }
            }
        }
    }
}

@Composable
private fun CopyButton(message: ChatMessage, label: String, onCopy: (String) -> Unit) {
    Box(Modifier.size(ControlSize.MetaRow).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label) { onCopy(message.text) }
        .testTag("copy:${message.id}"), contentAlignment = Alignment.Center) {
        Icon(MobileIcons.Copy, label, Modifier.size(IconSize.Meta), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageMenu(expanded: Boolean, onDismiss: () -> Unit, labels: Map<MessageAction, String>, perform: (MessageAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        messageActions.forEach { action -> DropdownMenuItem(text = { Text(labels.getValue(action)) }, onClick = { perform(action) }) }
    }
}

/** Search shows literal source so match offsets never drift through Markdown formatting. */
@Composable
private fun SearchableMessageText(message: ChatMessage, query: String, selected: SearchMatch?) {
    val colors = MaterialTheme.colorScheme
    val ranges = remember(message.text, query) { searchHistory(listOf(message), query) }
    val annotated = remember(message.text, ranges, selected, colors) { buildAnnotatedString {
        append(message.text)
        ranges.forEach { range -> addStyle(SpanStyle(
            background = if (range == selected) colors.primary else colors.secondaryContainer,
            color = if (range == selected) colors.onPrimary else colors.onSecondaryContainer), range.start, range.end) }
    } }
    val requester = remember { BringIntoViewRequester() }
    var layout by remember(message.text) { mutableStateOf<TextLayoutResult?>(null) }
    LaunchedEffect(selected, layout) {
        val result = layout
        if (selected != null && result != null && selected.start < result.layoutInput.text.length) {
            requester.bringIntoView(result.getBoundingBox(selected.start))
        }
    }
    Text(annotated, Modifier.bringIntoViewRequester(requester).testTag("search_text:${message.id}"),
        style = MaterialTheme.typography.bodyLarge, onTextLayout = { layout = it })
}
