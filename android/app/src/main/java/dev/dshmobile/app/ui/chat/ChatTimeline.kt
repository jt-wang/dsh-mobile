package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyListState
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.motionEnabled

/**
 * Keeps the newest content in view. With motion, a short hop (the live reply growing, or one or two new
 * items) is animated; a long jump stays instant so opening a chat never "scrolls through" history.
 */
internal suspend fun followToEnd(list: LazyListState, lastIndex: Int, motion: Boolean) {
    val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
    if (motion && lastVisible >= 0 && lastIndex - lastVisible <= 2 && list.layoutInfo.totalItemsCount > 0) {
        val info = list.layoutInfo
        val tail = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (tail != null) {
            // Already laid out: animate exactly the remaining distance to the bottom edge.
            val remaining = (tail.offset + tail.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
            if (remaining > 0f) list.animateScrollBy(remaining, androidx.compose.animation.core.tween(Motion.Medium, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else list.animateScrollToItem(lastIndex)
        list.scrollBy(Float.MAX_VALUE)
    } else { list.scrollToItem(lastIndex); list.scrollBy(Float.MAX_VALUE) }
}

@Composable
internal fun ChatTimeline(snapshot: SessionSnapshot, pending: PendingCommand?, onCopy: (String) -> Unit, modifier: Modifier = Modifier,
    searchQuery: String = "", selectedMatch: SearchMatch? = null,
    questions: (@Composable () -> Unit)? = null,
    media: (@Composable (ChatMessage) -> Unit)? = null,
    questionTarget: String? = null,
    classify: suspend (List<ChatMessage>, List<ChatItem>) -> List<ChatItem> = { messages, previous ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { chatItems(messages, previous) }
    }) {
    val list = rememberLazyListState()
    val motion = motionEnabled
    // New items fade in where they land; existing rows do not glide, so following and restored scroll stay exact.
    val itemFade = remember(motion) { if (motion) Motion.tween<Float>(Motion.Medium, true) else null }
    val autoScrolling = remember { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) }
    var unread by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val unconfirmed = pending?.takeIf { it.kind == "send" && it.sessionId == snapshot.session.id && dev.dshmobile.app.ui.components.hasPromptContent(it) && snapshot.messages.none { message -> message.requestId == it.requestId } }
    // Classify off the composition thread; keep prior overlapping groups for stable identities.
    val projection by produceState<TimelineProjection?>(null, TimelineInputKey(snapshot.messages)) {
        val input = snapshot.messages
        val previous = value?.items.orEmpty()
        value = TimelineProjection(input, classify(input, previous))
    }
    // Search includes all loaded messages, including normally collapsed service activity.
    val searchItems = remember(snapshot.messages, searchQuery.isNotBlank()) {
        if (searchQuery.isNotBlank()) snapshot.messages.map { ChatItem.Message(it) } else null
    }
    val visible = searchItems ?: visibleTimeline(snapshot.messages, projection)
    val loading = visible == null
    val timeline = visible.orEmpty()
    val messageCount = timeline.size + if (unconfirmed != null) 1 else 0
    val totalItems = messageCount + (if (snapshot.hasMore) 1 else 0) + (if (questions != null) 1 else 0)
    LaunchedEffect(list, threshold) {
        snapshotFlow {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            val nearBottom = last == null || (last.index == list.layoutInfo.totalItemsCount - 1 && last.offset + last.size - list.layoutInfo.viewportEndOffset <= threshold)
            // Our own animated follow is a scroll too; only the user's scrolling decides following.
            (list.isScrollInProgress && !autoScrolling.value) to nearBottom
        }.distinctUntilChanged().collect { (scrolling, nearBottom) ->
            if (scrolling) { following = nearBottom; if (nearBottom) unread = false }
        }
    }
    // The keyboard (and the activity row/composer growing) shrinks the viewport frame by frame; while
    // following, keep the newest line pinned to the bottom edge in the same frame instead of letting
    // it slide under the composer and jump back afterwards.
    val searching by rememberUpdatedState(searchQuery.isNotBlank())
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.viewportSize.height }.distinctUntilChanged().collect {
            if (following && !autoScrolling.value && !searching && list.layoutInfo.totalItemsCount > 0) {
                autoScrolling.value = true
                try { list.scrollBy(Float.MAX_VALUE) } finally { autoScrolling.value = false }
            }
        }
    }
    LaunchedEffect(questionTarget, questions != null, loading) {
        if (questionTarget != null && questions != null && !loading) { following = true; list.scrollToItem((if (snapshot.hasMore) 1 else 0) + timeline.size) }
    }
    LaunchedEffect(messageCount, timeline, loading, questions != null) {
        if (loading || searchQuery.isNotBlank()) return@LaunchedEffect
        if (following && totalItems > 0) {
            autoScrolling.value = true
            try { followToEnd(list, totalItems - 1, motion) } finally { autoScrolling.value = false }
        }
        else if (messageCount > 0) unread = true
    }
    LaunchedEffect(selectedMatch, timeline, snapshot.hasMore) {
        if (selectedMatch != null) {
            val index = timeline.indexOfFirst { it.id == selectedMatch.messageId }
            if (index >= 0) {
                following = false
                list.scrollToItem(index + if (snapshot.hasMore) 1 else 0)
            }
        }
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp).fillMaxSize().testTag("chat_timeline"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (snapshot.hasMore) item(key = LazyItemKeys.HISTORY_LIMIT) {
                Text(stringResource(R.string.mobile_history_limit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) item(key = "classification-loading") {
                val loadingLabel = stringResource(R.string.mobile_loading)
                // The spinner is drawn; expose it to TalkBack as indeterminate progress, as the old Material indicator did.
                dev.dshmobile.app.ui.components.Spinner(Modifier.testTag("chat_classification_loading").then(Modifier.semantics {
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate
                    contentDescription = loadingLabel
                }), size = 20.dp)
            }
            if (!loading && timeline.isEmpty() && unconfirmed == null) item(key = LazyItemKeys.EMPTY_CONVERSATION) {
                Text(stringResource(R.string.mobile_empty_conversation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(timeline, key = { if (it is ChatItem.Activity) "activity:${it.id}" else LazyItemKeys.message(it.id) },
                contentType = { if (it is ChatItem.Activity) "activity" else "message" }) { item ->
                Box(Modifier.animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)) {
                    when (item) {
                        is ChatItem.Message -> MessageItem(item.message, onCopy, searchQuery,
                            selectedMatch?.takeIf { it.messageId == item.id },
                            attachments = if (media != null && item.message.attachments.isNotEmpty()) ({ media(item.message) }) else null)
                        is ChatItem.Activity -> AgentActivityGroup(item, onCopy)
                    }
                }
            }
            if (questions != null) item(key = "phone-questions") {
                Box(Modifier.animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade)) { questions() }
            }
            unconfirmed?.let { command -> item(key = LazyItemKeys.pending(command.requestId)) {
                Column(Modifier.animateItem(fadeInSpec = itemFade, placementSpec = null, fadeOutSpec = itemFade), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.mobile_unconfirmed_message), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { dev.dshmobile.app.ui.components.PromptContent(command) }
                }
            } }
        }
        // Secondary navigation: a tonal pill with a hairline, never louder than Send; Material keeps the 48dp target.
        // It lifts in and drops out; at large font it grows in height and ellipsizes instead of clipping.
        AnimatedVisibility(unread && !following && searchQuery.isBlank(), Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 20.dp, end = 20.dp),
            enter = Motion.floatIn(motion), exit = Motion.floatOut(motion), label = "new messages") {
            FilledTonalButton(shape = androidx.compose.foundation.shape.CircleShape,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = Spacing.XS, bottom = Spacing.XS), onClick = {
                following = true; unread = false
                scope.launch {
                    if (list.layoutInfo.totalItemsCount > 0) {
                        autoScrolling.value = true
                        try { followToEnd(list, list.layoutInfo.totalItemsCount - 1, motion) } finally { autoScrolling.value = false }
                    }
                }
            }, modifier = Modifier.heightIn(min = ControlSize.Pill).testTag("new_messages")) {
                Icon(MobileIcons.ChevronDown, null, Modifier.size(IconSize.Meta))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.mobile_new_messages), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
