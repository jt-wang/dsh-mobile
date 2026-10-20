package dev.dshmobile.app.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.R

internal const val ALERT_GROUP = "dev.dshmobile.alerts"
internal const val SUMMARY_TAG = "dev.dshmobile.alert.summary"

// Bootstrap once after process death, then own the desired set synchronously. Platform
// notify/cancel acknowledgement is asynchronous and must never be used for recounting.
private var liveAlerts: MutableMap<String, Notification>? = null
internal fun clearAlertSummaryState() { synchronized(NotificationPosting) { liveAlerts = mutableMapOf() } }

/** Explicit summary never falls back to SystemUI's MAIN action while one question is hidden. */
internal fun updateAlertSummary(context: Context, added: Pair<String, Notification>? = null, removed: String? = null) = synchronized(NotificationPosting) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val live = liveAlerts ?: manager.activeNotifications.filter { it.id == 2 && it.notification.group == ALERT_GROUP && it.tag != null }
        .associate { it.tag to it.notification }.toMutableMap().also { liveAlerts = it }
    removed?.let { live.remove(it) }
    added?.let { live[it.first] = it.second }
    val alerts = live.values.toList()
    if (alerts.isEmpty()) { NotificationPosting.cancel(context, SUMMARY_TAG, 3); return@synchronized }
    val localized = dev.dshmobile.app.locale.appLanguageContext(context)
    val list = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java)
        .setAction("dev.dshmobile.OPEN_CHAT_LIST").putExtra("notificationChatList", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val summary = NotificationCompat.Builder(context, alerts.last().channelId)
        .setSmallIcon(R.drawable.ic_launcher_foreground).setGroup(ALERT_GROUP).setGroupSummary(true)
        .setContentTitle(localized.getString(R.string.app_name)).setContentText(localized.getString(R.string.notif_generic))
        .setNumber(alerts.size).setContentIntent(if (alerts.size == 1) alerts.single().contentIntent else list)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setSilent(true).setAutoCancel(true).build()
    manager.notify(SUMMARY_TAG, 3, summary)
}
