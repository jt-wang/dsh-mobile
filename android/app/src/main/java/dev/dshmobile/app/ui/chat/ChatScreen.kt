package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.ClipData
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.ControlSize
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(state: MobileState, model: MobileViewModel, requestedSession: String?, onBack: () -> Unit, onConnection: () -> Unit) {
    val snackbars = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.mobile_copied)
    val haptic = rememberActionHaptic()
    val refreshChat = {
        if (requestedSession != null) model.selectSession(requestedSession)
        else model.refresh()
    }
    val onCopy: (String) -> Unit = { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("DSH", text))); haptic(); snackbars.showSnackbar(copied) } }
    var cancel by remember { mutableStateOf(false) }
    var searching by androidx.compose.runtime.saveable.rememberSaveable(requestedSession) { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable(requestedSession) { mutableStateOf("") }
    var matchIndex by remember(requestedSession, query) { mutableIntStateOf(0) }
    val matches = remember(state.snapshot?.messages, query) { searchHistory(state.snapshot?.messages.orEmpty(), query) }
    val selectedMatch = matches.getOrNull(matchIndex.coerceAtMost(matches.lastIndex))
    val snapshot = state.snapshot?.takeIf { requestedSession == null || it.session.id == requestedSession }
    val visibleState = if (snapshot == null) state.copy(snapshot = null) else state
    val header = snapshot?.session ?: state.sessions.firstOrNull { it.id == requestedSession }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbars) },
        topBar = { CenterAlignedTopAppBar(navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("chat_drawer")) { Icon(MobileIcons.ChevronLeft, stringResource(R.string.mobile_chats), Modifier.size(IconSize.Action)) }
        }, title = {
            // The title block doubles as the connection affordance; acceptance waits on its state on every screen.
            // One tap target opens connection details; the title stays its own heading node and the
            // subtitle row carries connection_state (enum stateDescription + polite live region).
            Column(Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = stringResource(R.string.mobile_connection_details), onClick = onConnection)
                .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(header?.title?.ifBlank { stringResource(R.string.mobile_untitled) } ?: stringResource(R.string.mobile_chats),
                    Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                val connectionDescription = connectionLabel(state.connection)
                Row(Modifier.testTag("connection_state").semantics(mergeDescendants = true) {
                    stateDescription = connectionDescription
                    liveRegion = LiveRegionMode.Polite
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Off-line the issue strip writes the label; the subtitle keeps the project and adds only the dot.
                    if (state.connection != ConnectionState.ONLINE) {
                        val label = connectionLabel(state.connection)
                        StatusDot(connectionColor(state.connection), Modifier.semantics { contentDescription = label })
                    }
                    Text(header?.let { workspaceName(state, it.workspaceId) } ?: state.hostName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, actions = {
            Box {
                var more by remember { mutableStateOf(false) }
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("chat_more")) { Icon(MobileIcons.More, stringResource(R.string.mobile_more_actions), Modifier.size(IconSize.Action)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.mobile_search_history)) }, leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(IconSize.Row)) },
                        enabled = snapshot != null, modifier = Modifier.testTag("search_history"),
                        onClick = { more = false; searching = !searching; if (!searching) query = "" })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mobile_refresh)) }, leadingIcon = { Icon(MobileIcons.Refresh, null, Modifier.size(IconSize.Row)) },
                        enabled = !state.busy, modifier = Modifier.testTag("refresh_chat"), onClick = { more = false; refreshChat() })

                }
            }
        }, colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = {
            Column {
                snapshot?.takeIf { it.activity in listOf("running", "waiting") || (it.activity == "idle" && it.session.runningSubagents > 0) }?.let { ActivityRow(it) }
                Composer(visibleState, model, onCancel = { cancel = true })
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("chat_screen")) {
            if (searching) HistorySearchBar(query, { query = it }, matches.size, if (selectedMatch == null) -1 else matches.indexOf(selectedMatch),
                onPrevious = { matchIndex = nextSearchIndex(matches.indexOf(selectedMatch), matches.size, -1) },
                onNext = { matchIndex = nextSearchIndex(matches.indexOf(selectedMatch), matches.size, 1) },
                onClose = { searching = false; query = "" })
            val staleText = if (snapshot != null && state.snapshotStale)
                stringResource(if (state.busy || model.interactionBlocked) R.string.mobile_short_syncing else R.string.mobile_stale_state) else null
            // Off-line, the stale-state explanation is the strip's second row instead of a separate third line.
            ConnectionIssueStrip(state, refreshChat, detail = staleText)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.S)) }
            if (state.demo) {
                // Opaque band with a hairline: the timeline scrolls under an edge, not under floating text.
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = Spacing.Gutter, vertical = 6.dp)) { DemoBadge() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (staleText != null && state.connection == ConnectionState.ONLINE) Text(staleText,
                Modifier.padding(horizontal = Spacing.Gutter, vertical = Spacing.XS).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot?.let {
                if (model.taskNotSentIn(it.session.id, model.editorDraft)) Text(stringResource(R.string.mobile_task_not_sent), Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("task_not_sent").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = LocalMobileColors.current.warning)
                if (!executable(state)) Notice(stringResource(R.string.mobile_read_only_notice))
                if (it.activity == "waiting") Notice(stringResource(R.string.mobile_desktop_notice))
                else if (it.notice != null) Notice(stringResource(R.string.mobile_partial_information))
                if (it.activity !in listOf("idle", "running", "waiting")) Notice(stringResource(R.string.mobile_unknown_activity))
                key(it.session.id) { ChatTimeline(it, state.pending, onCopy, Modifier.weight(1f), searchQuery = query, selectedMatch = selectedMatch) }
            } ?: Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.busy || model.interactionBlocked) CircularProgressIndicator()
                else EmptyState(
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_title else R.string.mobile_offline),
                    stringResource(if (state.connection == ConnectionState.ONLINE) R.string.mobile_chat_unavailable_body else R.string.mobile_history_unavailable))
            }
        }
    }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text(stringResource(R.string.mobile_cancel_title)) },
        text = { Text(stringResource(R.string.mobile_cancel_explanation)) },
        confirmButton = { TextButton(onClick = { cancel = false; model.cancelRun(haptic) }, enabled = snapshot != null && canCancel(visibleState) && !model.interactionBlocked, modifier = Modifier.testTag("stop_run_confirm")) { Text(stringResource(R.string.mobile_cancel_run)) } },
        dismissButton = { TextButton(onClick = { cancel = false }) { Text(stringResource(R.string.mobile_keep_running)) } })
}

@Composable
private fun ActivityRow(snapshot: SessionSnapshot) {
    val started by produceState<Long?>(snapshot.activityDetail?.turnStartedAt, snapshot.messages, snapshot.activityDetail) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { activityStartedAt(snapshot) }
    }
    var now by remember(snapshot.session.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.session.id, snapshot.activity, started) {
        now = System.currentTimeMillis()
        while (snapshot.activity == "running") { delay(30_000); now = System.currentTimeMillis() }
    }
    val elapsed = activityElapsed(started, now)
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 24.dp).testTag("activity_row").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        run {
            if (snapshot.activity == "running" || (snapshot.activity == "idle" && snapshot.session.runningSubagents > 0)) Spinner(size = ControlSize.StatusGlyph, strokeWidth = ControlSize.StatusStroke) else StatusDot(LocalMobileColors.current.warningIndicator)
            Text(when {
                snapshot.activity == "idle" && snapshot.session.runningSubagents > 0 -> stringResource(R.string.mobile_subagents_activity, snapshot.session.runningSubagents)
                snapshot.activity == "waiting" -> stringResource(R.string.mobile_waiting_desktop)
                snapshot.activityDetail == null -> when (elapsed) {
                    null -> stringResource(R.string.mobile_agent_working)
                    ActivityElapsed.LessThanMinute -> stringResource(R.string.mobile_agent_seconds)
                    is ActivityElapsed.Minutes -> stringResource(R.string.mobile_agent_minutes, elapsed.value)
                    is ActivityElapsed.Hours -> stringResource(R.string.mobile_agent_hours, elapsed.hours, elapsed.minutes)
                    is ActivityElapsed.Days -> stringResource(R.string.mobile_agent_days, elapsed.value)
                }
                else -> {
                    val base = stringResource(R.string.mobile_agent_working)
                    val duration = when (elapsed) {
                        null -> null
                        ActivityElapsed.LessThanMinute -> stringResource(R.string.mobile_activity_seconds)
                        is ActivityElapsed.Minutes -> stringResource(R.string.mobile_activity_minutes, elapsed.value)
                        is ActivityElapsed.Hours -> stringResource(R.string.mobile_activity_hours, elapsed.hours, elapsed.minutes)
                        is ActivityElapsed.Days -> stringResource(R.string.mobile_activity_days, elapsed.value)
                    }
                    listOfNotNull(base, snapshot.activityDetail?.tool, duration).joinToString(" · ")
                }
            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
