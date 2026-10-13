package dev.dshmobile.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.LazyItemKeys
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.*
import dev.dshmobile.app.ui.theme.LocalMobileColors
import dev.dshmobile.app.ui.theme.tabular

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreen(state: MobileState, query: String, onQuery: (String) -> Unit, projectId: String?, onProject: (String?) -> Unit,
    onSelect: (String) -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, onConnection: () -> Unit, onRefresh: () -> Unit,
    interactionBlocked: Boolean, pendingDelivery: @Composable () -> Unit) {
    val sessions = if (state.capabilities?.sessions == true) state.sessions else emptyList()
    val projects = remember(state.workspaces, sessions) { projectSummaries(state.workspaces, sessions) }
    val visible = remember(sessions, query, projectId) { filterSessions(sessions, projectId, query) }
    val locale = LocalConfiguration.current.locales[0]
    val now = remember(visible, locale, state.lastSyncedAt) { System.currentTimeMillis() }
    val groups = remember(visible, locale, now) { groupSessionsByDate(visible, now, locale = locale) }
    val list = rememberLazyListState()
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val canStart = canCreate(state) && !interactionBlocked
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                        Icon(MobileIcons.Settings, stringResource(R.string.mobile_settings_connection))
                    }
                },
                title = { HomeTitle(state, onConnection) },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) onQuery("") }, modifier = Modifier.testTag("home_search_toggle")) {
                        Icon(if (searchOpen) MobileIcons.Close else MobileIcons.Search,
                            stringResource(if (searchOpen) R.string.mobile_clear_search else R.string.mobile_search_chats))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = { NewTaskBar(canStart, onNewChat) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("home_screen")) {
            ConnectionIssueStrip(state, onRefresh)
            if (state.connection == ConnectionState.ONLINE) state.error?.let { ErrorText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            pendingDelivery()
            PullToRefreshBox(isRefreshing = state.busy && state.connection == ConnectionState.SYNCING, onRefresh = onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("chat_list"), contentPadding = PaddingValues(bottom = 16.dp)) {
                    // Filters scroll with the list so large landscape text cannot consume the row viewport.
                    item(key = "list-filters") {
                        Column {
                            if (searchOpen) SearchField(query, onQuery)
                            LazyRow(Modifier.fillMaxWidth().testTag("project_filters"), contentPadding = PaddingValues(horizontal = 12.dp)) {
                                item(key = LazyItemKeys.ALL_PROJECTS) { ProjectTab(stringResource(R.string.mobile_all_projects), projectId == null, "project_all") { onProject(null) } }
                                items(projects, key = { LazyItemKeys.project(it.workspace.id) }) { project ->
                                    ProjectTab(stringResource(R.string.mobile_project_filter, project.workspace.name, project.chatCount), projectId == project.workspace.id,
                                        "project_filter_${project.workspace.id}") { onProject(project.workspace.id) }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            if (state.demo) Text(stringResource(R.string.mobile_demo_tag), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).testTag("demo_label"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (visible.isEmpty()) item(key = LazyItemKeys.EMPTY_CHATS) {
                        EmptyState(
                            title = stringResource(when {
                                query.isNotBlank() -> R.string.mobile_no_search_title
                                state.connection != ConnectionState.ONLINE && sessions.isEmpty() -> R.string.mobile_offline
                                projectId != null -> R.string.mobile_no_project_chats
                                else -> R.string.mobile_home_empty_title
                            }),
                            body = stringResource(when {
                                query.isNotBlank() -> R.string.mobile_no_search_body
                                state.connection != ConnectionState.ONLINE && sessions.isEmpty() -> R.string.mobile_history_unavailable
                                projectId != null -> R.string.mobile_no_project_body
                                else -> R.string.mobile_home_empty_body
                            }), modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            action = if (query.isNotBlank()) { { TextButton(onClick = { onQuery("") }) { Text(stringResource(R.string.mobile_clear_search)) } } } else null)
                    }
                    groups.forEach { group ->
                        item(key = LazyItemKeys.section(group.section.name)) {
                            Text(stringResource(when (group.section) {
                                DateSection.TODAY -> R.string.mobile_today
                                DateSection.YESTERDAY -> R.string.mobile_yesterday
                                DateSection.THIS_WEEK -> R.string.mobile_this_week
                                DateSection.EARLIER -> R.string.mobile_earlier
                            }), Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(group.sessions, key = { LazyItemKeys.session(it.id) }, contentType = { "session" }) { session ->
                            SessionRow(session, workspaceName(state, session.workspaceId),
                                timestamp = sessionTimestamp(session.updatedAt, group.section, now, locale = locale),
                                readOnly = !session.canExecute || state.workspaces.none { it.id == session.workspaceId && it.canExecute },
                                enabled = !state.busy && !interactionBlocked && (state.connection == ConnectionState.ONLINE ||
                                    (state.connection in listOf(ConnectionState.OFFLINE, ConnectionState.DISCONNECTED) && state.snapshot?.session?.id == session.id)),
                                onClick = { onSelect(session.id) })
                        }
                    }
                    if (state.sessionsTruncated) item(key = LazyItemKeys.TRUNCATED_CHATS) {
                        Text(stringResource(R.string.mobile_sessions_truncated), Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Brand plus live connection state; the whole block opens connection details. */
@Composable
private fun HomeTitle(state: MobileState, onConnection: () -> Unit) {
    val label = connectionLabel(state.connection)
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onConnection).heightIn(min = 48.dp).padding(horizontal = 12.dp)
        .testTag("connection_state").semantics(mergeDescendants = true) {
            stateDescription = state.connection.name
            liveRegion = LiveRegionMode.Polite
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.mobile_brand), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(connectionColor(state.connection), pulse = state.connection in listOf(ConnectionState.CONNECTING, ConnectionState.SYNCING))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(stringResource(R.string.mobile_search_chats)) },
        leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(20.dp)) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(MobileIcons.Close, stringResource(R.string.mobile_clear_search), Modifier.size(20.dp)) } },
        shape = CircleShape, colors = TextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant, focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("chat_search"))
}

/** Text tab with an underline; selection semantics stay native for TalkBack and tests. */
@Composable
private fun ProjectTab(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val ink = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.onSurface
    // The underline is drawn, not laid out, so the tab hugs its label instead of growing to the max width.
    Box(Modifier.widthIn(max = 280.dp).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).selectable(selected = selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 12.dp).testTag(tag)
        .drawBehind { if (selected) drawRoundRect(line, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx())) },
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionRow(session: SessionSummary, project: String, timestamp: String, readOnly: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val details = listOfNotNull(project, if (session.running) stringResource(R.string.mobile_working) else null,
        if (readOnly) stringResource(R.string.mobile_read_only) else null).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp).testTag("session_${session.id}"),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
            if (session.running) Spinner(size = 15.dp) else IdleRing()
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(session.title.ifBlank { stringResource(R.string.mobile_untitled) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timestamp, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall.tabular(), color = muted, maxLines = 1)
            }
            Text(details, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Composer-shaped entry point: one tap opens project, preset and task text together. */
@Composable
private fun NewTaskBar(enabled: Boolean, onNewChat: () -> Unit) {
    val accents = LocalMobileColors.current
    val label = stringResource(R.string.mobile_new_chat)
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, onClick = onNewChat)
            .padding(start = 20.dp, end = 6.dp)
            .testTag("new_chat").semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (!enabled) disabled()
            }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mobile_new_task_hint), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(44.dp).background(if (enabled) accents.action else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(MobileIcons.Plus, null, Modifier.size(22.dp), tint = if (enabled) accents.onAction else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
