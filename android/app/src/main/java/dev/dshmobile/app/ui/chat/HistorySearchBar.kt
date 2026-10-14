package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.theme.IconSize

@Composable
internal fun HistorySearchBar(query: String, onQuery: (String) -> Unit, count: Int, index: Int,
    onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(R.string.mobile_search_history)) },
            leadingIcon = { Icon(MobileIcons.Search, null, Modifier.size(IconSize.Row)) },
            shape = CircleShape, colors = TextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                unfocusedPlaceholderColor = muted, focusedPlaceholderColor = muted),
            modifier = Modifier.fillMaxWidth().testTag("history_query"), trailingIcon = {
                IconButton(onClick = onClose) { Icon(MobileIcons.Close, stringResource(R.string.mobile_close_search), Modifier.size(IconSize.Row)) }
            })
        Row(Modifier.heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (query.isBlank()) stringResource(R.string.mobile_search_loaded_only)
                else if (count == 0) stringResource(R.string.mobile_search_no_matches) else stringResource(R.string.mobile_search_count, index + 1, count),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = muted)
            if (query.isNotBlank()) {
                IconButton(onClick = onPrevious, enabled = count > 0, modifier = Modifier.testTag("history_previous")) {
                    Icon(MobileIcons.ChevronDown, stringResource(R.string.mobile_search_previous), Modifier.size(IconSize.Row).rotate(180f))
                }
                IconButton(onClick = onNext, enabled = count > 0, modifier = Modifier.testTag("history_next")) {
                    Icon(MobileIcons.ChevronDown, stringResource(R.string.mobile_search_next), Modifier.size(IconSize.Row))
                }
            }
        }
    }
}
