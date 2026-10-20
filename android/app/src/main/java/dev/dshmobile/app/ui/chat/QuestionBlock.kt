package dev.dshmobile.app.ui.chat

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.dshmobile.app.R
import dev.dshmobile.app.model.*
import dev.dshmobile.app.ui.MobileIcons
import dev.dshmobile.app.ui.components.Spinner
import dev.dshmobile.app.ui.theme.LocalMobileColors

/** Stateless business boundary: draft and submission belong to the ViewModel/repository. */
@Composable
internal fun QuestionBlock(pending: PendingQuestion, draft: QuestionAnswer?, readOnly: Boolean, online: Boolean, submitting: Boolean,
    error: Boolean, expired: Boolean = false, answered: QuestionAnswer? = null, focusTarget: Boolean = false, skipSupported: Boolean = false, skipped: Boolean = false, onSkip: () -> Unit = {}, onChange: (QuestionAnswer) -> Unit, onSubmit: (QuestionAnswer) -> Unit) {
    var step by rememberSaveable(pending.callId) { mutableIntStateOf(0) }
    var local by remember(pending.callId) { mutableStateOf(draft ?: QuestionAnswer(pending.questions.map { AnswerItem(it.id, emptyList()) })) }
    var edited by remember(pending.callId) { mutableStateOf(false) }
    LaunchedEffect(draft) { if (!edited && draft != null) local = draft }
    val accents = LocalMobileColors.current
    if (expired || answered != null || skipped || pending.state == "continued") {
        Row(Modifier.fillMaxWidth().testTag("question_record_${pending.callId}"), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (answered != null) MobileIcons.Check else MobileIcons.Clock, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Column { Text(pending.questions.firstOrNull()?.question.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text(if (skipped) stringResource(R.string.mobile_question_skipped) else if (expired || pending.state == "continued") stringResource(R.string.mobile_question_expired) else stringResource(R.string.mobile_question_answer, answered?.answers.orEmpty().flatMap { it.selected + listOfNotNull(it.custom) }.joinToString(", ")), style = MaterialTheme.typography.bodyMedium) }
        }
        return
    }
    val question = pending.questions.getOrNull(step.coerceIn(0, pending.questions.lastIndex)) ?: return
    val answer = local.answers.find { it.id == question.id } ?: AnswerItem(question.id, emptyList())
    fun change(value: AnswerItem) {
        edited = true
        local = QuestionAnswer(pending.questions.map { q -> if (q.id == value.id) value else local.answers.find { it.id == q.id } ?: AnswerItem(q.id, emptyList()) })
        onChange(local)
    }
    val previous = stringResource(R.string.mobile_question_prev)
    val enabled = !readOnly && !submitting
    Column(Modifier.fillMaxWidth().testTag("question_${pending.callId}").semantics {
        heading(); liveRegion = LiveRegionMode.Polite
        customActions = if (step > 0) listOf(CustomAccessibilityAction(previous) { step--; true }) else emptyList()
    }.pointerInput(step) {
        var distance = 0f
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onHorizontalDrag = { _, amount -> distance += amount }, onDragEnd = { if (distance > 80 && step > 0) step-- })
    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MobileIcons.Question, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
            Text(question.header.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending.questions.size > 1) Text("${step + 1} / ${pending.questions.size}", style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = "${step + 1} / ${pending.questions.size}" })
        }
        if (focusTarget) {
            val color = MaterialTheme.colorScheme.onSurface
            androidx.compose.ui.viewinterop.AndroidView(factory = { context ->
                android.widget.TextView(context).apply {
                    text = question.question; textSize = 16f
                    setTextColor(android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                    post {
                        val focused = performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                        dev.dshmobile.app.data.notificationTrace("question_accessibility_focus granted=$focused")
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), update = { it.text = question.question })
        } else Text(question.question, style = MaterialTheme.typography.bodyLarge)
        question.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.selectableGroup()) {
            question.options.forEachIndexed { index, option ->
                val selected = option.label in answer.selected
                val action = {
                    change(if (question.multiSelect) answer.copy(selected = if (selected) answer.selected - option.label else answer.selected + option.label)
                    else answer.copy(selected = listOf(option.label), custom = null))
                }
                val selection = if (question.multiSelect) Modifier.toggleable(selected, enabled, Role.Checkbox) { action() }
                    else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = action)
                Row(Modifier.fillMaxWidth().then(selection).heightIn(min = 48.dp).testTag("question_option_$index"), verticalAlignment = Alignment.CenterVertically) {
                    if (question.multiSelect) Checkbox(selected, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface, checkmarkColor = MaterialTheme.colorScheme.surface))
                    else RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) { Text(if (option.recommended) option.label.replace(Regex("\\s*(?:\\((?:Recommended|推荐)\\)|（(?:Recommended|推荐)）)\\s*$", RegexOption.IGNORE_CASE), "") else option.label, style = MaterialTheme.typography.bodyLarge)
                        if (option.recommended) Text(stringResource(R.string.mobile_question_recommended), style = MaterialTheme.typography.labelSmall)
                        option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            if (question.allowFreeText) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (question.multiSelect) Checkbox(!answer.custom.isNullOrBlank(), null, enabled = enabled)
                else RadioButton(!answer.custom.isNullOrBlank(), null, enabled = enabled)
                TextField(answer.custom.orEmpty(), { change(answer.copy(custom = PhoneRules.boundedAnswerText(it).takeIf { text -> text.isNotEmpty() }, selected = if (question.multiSelect) answer.selected else emptyList())) },
                    enabled = enabled, placeholder = { Text(stringResource(R.string.mobile_question_other_hint)) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).testTag("question_custom"), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        if (error) PhoneError(stringResource(R.string.mobile_question_send_failed))
        if (readOnly) Text(stringResource(R.string.mobile_question_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val selected = PhoneRules.validAnswer(pending.copy(questions = listOf(question)), QuestionAnswer(listOf(answer)))
            Button(onClick = { if (step < pending.questions.lastIndex) step++ else if (PhoneRules.validAnswer(pending, local, skipSupported)) onSubmit(local) },
                enabled = selected && !submitting && (step < pending.questions.lastIndex || online), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("question_submit"),
                colors = ButtonDefaults.buttonColors(containerColor = accents.action, contentColor = accents.onAction)) {
                if (submitting) { Spinner(size = 16.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(if (step < pending.questions.lastIndex) R.string.mobile_question_next else R.string.mobile_question_submit))
            }
            if (skipSupported) TextButton(onClick = {
                if (pending.questions.size > 1 && step < pending.questions.lastIndex) { change(AnswerItem(question.id, emptyList())); step++ }
                else if (pending.questions.size > 1) { change(AnswerItem(question.id, emptyList())); onSubmit(local) }
                else onSkip()
            }, enabled = online && !submitting, modifier = Modifier.testTag("question_skip")) { Text(stringResource(R.string.mobile_question_skip)) }
            if (step > 0) TextButton(onClick = { step-- }, enabled = !submitting, modifier = Modifier.testTag("question_previous")) { Text(previous) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
