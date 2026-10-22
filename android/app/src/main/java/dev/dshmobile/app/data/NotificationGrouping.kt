package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.dshmobile.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal const val ALERT_GROUP = "dev.dshmobile.alerts"
internal const val SUMMARY_TAG = "dev.dshmobile.alert.summary"
internal const val SUMMARY_CHANNEL = "chat_summary_v1"
internal const val ALERT_DEVICE = "dev.dshmobile.alertDevice"
internal const val ALERT_CHAT = "dev.dshmobile.alertChat"
private const val SUMMARY_CHATS = "dev.dshmobile.summaryChats"
private const val SUMMARY_ANSWERS = "dev.dshmobile.summaryAnswers"
private const val SUMMARY_ATTENTION = "dev.dshmobile.summaryAttention"

private var liveAlerts: MutableMap<String, Notification>? = null
private var summaryJob: Job? = null
private var summaryRevision = 0L
internal fun clearAlertSummaryState() { synchronized(NotificationPosting) { liveAlerts = mutableMapOf(); summaryRevision++; summaryJob?.cancel(); summaryJob = null } }
internal fun desiredAlertNotification(tag: String): Notification? = synchronized(NotificationPosting) { liveAlerts?.get(tag) }
internal fun desiredAlertEvent(tag: String): String? = desiredAlertNotification(tag)?.extras?.getString("dev.dshmobile.alertEvent")
internal fun summaryModel(notification: Notification) = NotificationSummary(notification.extras.getInt(SUMMARY_CHATS, notification.number),
    notification.extras.getInt(SUMMARY_ANSWERS), notification.extras.getInt(SUMMARY_ATTENTION))
internal fun summaryTitle(context: Context, model: NotificationSummary): String = context.resources.getQuantityString(R.plurals.notif_summary_chats, model.chats, model.chats)
internal fun summaryBody(context: Context, model: NotificationSummary): String = listOfNotNull(
    model.answers.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.notif_summary_answers, it, it) },
    model.attention.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.notif_summary_attention, it, it) }
).joinToString(" · ")

/** Desired set is synchronous; asynchronous platform acknowledgement is not a recount. */
internal fun updateAlertSummary(context: Context, added: Pair<String, Notification>? = null, removed: String? = null) = synchronized(NotificationPosting) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val live = liveAlerts ?: manager.activeNotifications.filter { it.id == 2 && it.notification.group == ALERT_GROUP && it.tag != null }
        .associate { it.tag to it.notification }.toMutableMap().also { liveAlerts = it }
    removed?.let { live.remove(it) }; added?.let { live[it.first] = it.second }
    val revision = ++summaryRevision
    summaryJob?.cancel(); summaryJob = null
    if (live.isEmpty()) { NotificationPosting.cancel(context, SUMMARY_TAG, 3); return@synchronized }
    val localized = dev.dshmobile.app.locale.appLanguageContext(context)
    // Existing channels are re-registered only by name, preserving all user overrides.
    val channel = manager.getNotificationChannel(SUMMARY_CHANNEL)
    if (channel == null) manager.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, localized.getString(R.string.notif_summary_channel), NotificationManager.IMPORTANCE_LOW).apply {
        setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }) else { channel.name = localized.getString(R.string.notif_summary_channel); manager.createNotificationChannel(channel) }
    val identities = live.map { (tag, n) ->
        // Old tags cannot unambiguously separate device/chat IDs containing ':'. Keep
        // the complete legacy pair rather than truncating either ID; new alerts use extras.
        NotificationAlertIdentity(n.extras.getString(ALERT_DEVICE) ?: "legacy", n.extras.getString(ALERT_CHAT) ?: tag.substringBeforeLast(':'),
            if (tag.endsWith(":attention") || n.extras.getString("dev.dshmobile.alertKind") == "attention") "attention" else "answers")
    }
    val model = notificationSummary(identities)
    val summary = NotificationCompat.Builder(context, SUMMARY_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground).setGroup(ALERT_GROUP).setGroupSummary(true)
        .addExtras(Bundle().apply { putInt(SUMMARY_CHATS, model.chats); putInt(SUMMARY_ANSWERS, model.answers); putInt(SUMMARY_ATTENTION, model.attention) })
        .setContentTitle(summaryTitle(localized, model)).setContentText(summaryBody(localized, model))
        .setNumber(model.chats).setContentIntent(if (model.chats == 1) live.values.first().contentIntent ?: notificationChatListIntent(context) else notificationChatListIntent(context))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setSilent(true).setAutoCancel(true).build()
    // Child + summary back-to-back doubled the package enqueue rate and Android could
    // silently shed the final count. Coalesce desired-state updates in the posting lane.
    val application = context.applicationContext
    summaryJob = NotificationPosting.scope.launch {
        NotificationPosting.paced {
            synchronized(NotificationPosting) {
                if (revision == summaryRevision && !liveAlerts.isNullOrEmpty()) {
                    val localizedSummary = translatedNotification(application, 3, SUMMARY_TAG, summary) ?: summary
                    manager.notify(SUMMARY_TAG, 3, localizedSummary)
                }
            }
        }
    }
}
