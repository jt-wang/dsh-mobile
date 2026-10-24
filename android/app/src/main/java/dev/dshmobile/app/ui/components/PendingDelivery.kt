package dev.dshmobile.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.MobileState
import dev.dshmobile.app.model.MobileReducer
import androidx.compose.ui.text.style.TextOverflow
import dev.dshmobile.app.ui.MobileViewModel
import dev.dshmobile.app.ui.theme.LocalMobileColors
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.theme.AnimatedKeyed
import dev.dshmobile.app.ui.theme.IconSize
import dev.dshmobile.app.ui.theme.Motion
import dev.dshmobile.app.ui.theme.Spacing
import dev.dshmobile.app.ui.theme.motionEnabled

internal fun hasPromptContent(command: dev.dshmobile.app.model.PendingCommand) = !command.text.isNullOrBlank() || command.attachmentCount > 0

@Composable
internal fun PromptContent(command: dev.dshmobile.app.model.PendingCommand, maxLines: Int = Int.MAX_VALUE) {
    if (!command.text.isNullOrBlank()) Text(command.text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    // Attachments read as one group: a paper-clip gutter, the count as its heading and the names as a
    // quieter list aligned under it, so an attachment-only prompt still has a clear shape.
    if (command.attachmentCount > 0 || command.attachmentNames.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = Spacing.XXS),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(MobileIcons.Attach, null, Modifier.padding(top = Spacing.XXS).size(IconSize.Meta), tint = muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
            if (command.attachmentCount > 0) Text(stringResource(R.string.mobile_prompt_attachments, command.attachmentCount),
                modifier = Modifier.testTag("prompt_attachments_${command.requestId}"), style = MaterialTheme.typography.labelLarge)
            command.attachmentNames.forEach { name ->
                Text(name, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Also lives on Home: an uncertain create has no chat/composer yet, but must remain resolvable. */
@Composable
internal fun PendingDelivery(state: MobileState, model: MobileViewModel, modifier: Modifier = Modifier) {
    NotSentDelivery(state.sendNotSent && state.pending == null, !state.busy && !model.interactionBlocked, model::retryNotSent)
    val accents = LocalMobileColors.current
    // Accepted inbox prompts are informational local bubbles, not delivery warnings.
    val snapshot = state.snapshot
    val motion = motionEnabled
    // Cards slide open when a prompt is accepted and fold away once the transcript confirms or the user hides it.
    AnimatedKeyed(state.acceptedPrompts.filter { snapshot != null && it.sessionId == snapshot.session.id && !MobileReducer.reconcilesPrompt(it, snapshot) },
        key = { it.requestId }, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion)) { command ->
        run {
            val scope = rememberCoroutineScope()
            var confirmResend by remember { mutableStateOf(false) }
            var acting by remember { mutableStateOf(false) }
            val actions = state.localPromptActions
            val enabled = !acting && !state.busy && !model.interactionBlocked
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
                modifier = modifier.fillMaxWidth().testTag("accepted_prompt_${command.requestId}")) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(when (command.status) {
                        "queued" -> R.string.mobile_delivery_queued
                        "unconfirmed" -> R.string.mobile_delivery_unconfirmed
                        else -> R.string.mobile_delivery_accepted
                    }),
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    val queued = command.status == "queued"
                    PromptContent(command, maxLines = if (queued) 1 else 2)
                    // The local-only warning matters when the user is deciding about an unconfirmed prompt;
                    // on an ordinary queued item it doubled the card height.
                    if (!queued) Text(stringResource(R.string.mobile_local_prompt_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Actions wrap as whole buttons onto a second line instead of breaking a label in two.
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        TextButton(onClick = {
                            acting = true
                            scope.launch { try { actions?.dismiss?.invoke(command.requestId) } finally { acting = false } }
                        }, enabled = enabled && actions != null, modifier = Modifier.testTag("dismiss_prompt_${command.requestId}")) {
                            Text(stringResource(R.string.mobile_dismiss_local_prompt))
                        }
                        TextButton(onClick = { confirmResend = true }, enabled = enabled && actions != null && MobileReducer.canSend(state, command.text.orEmpty(), command.attachmentCount > 0),
                            modifier = Modifier.testTag("resend_prompt_${command.requestId}")) { Text(stringResource(R.string.mobile_resend_prompt)) }
                    }
                }
            }
            if (confirmResend) AlertDialog(onDismissRequest = { confirmResend = false },
                title = { Text(stringResource(R.string.mobile_resend_prompt)) },
                text = { Text(stringResource(R.string.mobile_resend_warning)) },
                confirmButton = { TextButton(onClick = {
                    confirmResend = false; acting = true
                    scope.launch { try { actions?.resend?.invoke(command.requestId) } finally { acting = false } }
                }, enabled = enabled && MobileReducer.canSend(state, command.text.orEmpty(), command.attachmentCount > 0), modifier = Modifier.testTag("resend_prompt_confirm")) { Text(stringResource(R.string.mobile_resend_prompt)) } },
                dismissButton = { TextButton(onClick = { confirmResend = false }) { Text(stringResource(R.string.mobile_back)) } })
        }
    }
    if (MobileReducer.blockingPromptCount(state) >= MobileReducer.MAX_ACCEPTED_PROMPTS) {
        Text(stringResource(R.string.mobile_queue_full), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val heldPending = remember { arrayOfNulls<dev.dshmobile.app.model.PendingCommand>(1) }
    if (state.pending != null) heldPending[0] = state.pending
    AnimatedVisibility(state.pending != null, enter = Motion.revealIn(motion), exit = Motion.revealOut(motion), label = "pending delivery") {
        heldPending[0]?.let { PendingCard(it, state, model, modifier) }
    }
}

@Composable
internal fun NotSentDelivery(visible: Boolean, enabled: Boolean, retry: () -> Unit) {
    if (!visible) return
    Surface(color = LocalMobileColors.current.warningContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("send_not_sent")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.mobile_send_not_sent), color = LocalMobileColors.current.warning,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = retry, enabled = enabled, modifier = Modifier.testTag("retry_not_sent")) { Text(stringResource(R.string.mobile_retry_generic)) }
        }
    }
}

@Composable
private fun PendingCard(pending: dev.dshmobile.app.model.PendingCommand, state: MobileState, model: MobileViewModel, modifier: Modifier) {
    val accents = LocalMobileColors.current
    var abandon by remember(pending.requestId) { mutableStateOf(false) }
    val normal = pending.status in listOf("sending", "pending", "accepted")
    Surface(color = if (normal) MaterialTheme.colorScheme.surfaceContainer else accents.warningContainer,
        shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(when {
                pending.kind == "cancel" && pending.status == "accepted" -> R.string.mobile_cancel_accepted
                pending.status == "accepted" -> R.string.mobile_delivery_accepted
                pending.status == "rejected" -> R.string.mobile_delivery_rejected
                pending.status == "uncertain" -> R.string.mobile_delivery_uncertain
                pending.status in listOf("pending", "sending") -> R.string.mobile_delivery_pending
                else -> R.string.mobile_delivery_unknown
            }), color = if (normal) MaterialTheme.colorScheme.onSurfaceVariant else accents.warning,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (pending.kind == "send") PromptContent(pending)
            TextButton(onClick = model::resolvePending, enabled = !state.busy, modifier = Modifier.testTag("resolve_pending")) { Text(stringResource(R.string.mobile_check_delivery)) }
            if (pending.status in listOf("uncertain", "accepted")) TextButton(onClick = { abandon = true }, enabled = !state.busy, modifier = Modifier.testTag("abandon_pending")) {
                Text(stringResource(R.string.mobile_abandon_pending))
            }
        }
    }
    if (abandon) AlertDialog(onDismissRequest = { abandon = false }, title = { Text(stringResource(R.string.mobile_abandon_title)) },
        text = { Text(stringResource(R.string.mobile_abandon_confirmation)) },
        confirmButton = { TextButton(onClick = { abandon = false; model.abandonPending() }, enabled = !state.busy, modifier = Modifier.testTag("abandon_pending_confirm")) { Text(stringResource(R.string.mobile_abandon_pending)) } },
        dismissButton = { TextButton(onClick = { abandon = false }) { Text(stringResource(R.string.mobile_back)) } })
}
