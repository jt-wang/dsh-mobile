package dev.dshmobile.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.dshmobile.app.R
import kotlinx.serialization.Serializable

@Serializable internal enum class AlertMode { SOUND, SILENT, OFF }
internal data class AlertChannel(val id: String, val attention: Boolean, val alert: Boolean)
internal val notificationChannels = listOf(
    AlertChannel("answers_alert_v2", false, true), AlertChannel("answers_silent_v2", false, false),
    AlertChannel("attention_alert_v2", true, true), AlertChannel("attention_silent_v2", true, false))
internal fun migrateNotificationChannels(existing: Set<String>, create: (AlertChannel) -> Unit, delete: (String) -> Unit) {
    notificationChannels.filterNot { it.id in existing }.forEach(create)
    listOf("answers", "attention").forEach(delete)
}
internal data class LegacyAlert(val importance: Int, val sound: Boolean)
internal fun migrateLegacyModes(local: NotificationLocal, appEnabled: Boolean, answers: LegacyAlert?, attention: LegacyAlert?): NotificationLocal {
    if (local.channelModesMigrated) return local
    fun mode(old: LegacyAlert?, current: AlertMode) = when {
        !appEnabled || old?.importance == 0 -> AlertMode.OFF
        old != null && (old.importance <= 2 || !old.sound) -> AlertMode.SILENT
        else -> current
    }
    return local.copy(answerMode = mode(answers, local.answerMode), attentionMode = mode(attention, local.attentionMode), channelModesMigrated = true)
}
/** Refresh only names on existing IDs. Never delete/rebuild channels or touch user-controlled settings. */
internal fun refreshChannelNames(existing: Set<String>, answers: String, attention: String, sound: String, silent: String,
    connection: String, rename: (String, String) -> Unit) {
    notificationChannels.filter { it.id in existing }.forEach { spec ->
        rename(spec.id, (if (spec.attention) attention else answers) + " · " + (if (spec.alert) sound else silent))
    }
    if ("connection" in existing) rename("connection", connection)
}

internal fun refreshNotificationChannelNames(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.getNotificationChannel(SUMMARY_CHANNEL)?.let { channel ->
        channel.name = context.getString(R.string.notif_summary_channel)
        manager.createNotificationChannel(channel)
    }
    refreshChannelNames(manager.notificationChannels.map { it.id }.toSet(),
        context.getString(R.string.notif_answers_channel), context.getString(R.string.notif_attention_channel),
        context.getString(R.string.notif_mode_sound), context.getString(R.string.notif_mode_silent),
        context.getString(R.string.notif_connection_channel)) { id, name ->
        manager.getNotificationChannel(id)?.let { channel ->
            channel.name = name
            // Re-register the existing object, preserving importance, sound, vibration and description.
            manager.createNotificationChannel(channel)
        }
    }
}

internal suspend fun ensureNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    fun legacy(id: String) = manager.getNotificationChannel(id)?.let { LegacyAlert(it.importance, it.sound != null) }
    // Persist the old user choice before deleting its only platform evidence.
    NotificationStore(context).update { migrateLegacyModes(it, manager.areNotificationsEnabled(), legacy("answers"), legacy("attention")) }
    migrateNotificationChannels(manager.notificationChannels.map { it.id }.toSet(), create = { spec ->
        val name = context.getString(if (spec.attention) R.string.notif_attention_channel else R.string.notif_answers_channel) +
            " · " + context.getString(if (spec.alert) R.string.notif_mode_sound else R.string.notif_mode_silent)
        val channel = NotificationChannel(spec.id, name, if (spec.alert) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
        channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        if (!spec.alert) { channel.setSound(null, null); channel.enableVibration(false) }
        manager.createNotificationChannel(channel)
    }, delete = manager::deleteNotificationChannel)
    refreshNotificationChannelNames(context)
}
internal fun notificationChannel(event: NotificationEvent, local: NotificationLocal, recovery: Boolean = false): String? {
    val mode = if (event.kind == "answer-finished") local.answerMode else local.attentionMode
    if (mode == AlertMode.OFF) return null
    val prefix = if (event.kind == "answer-finished") "answers" else "attention"
    return prefix + if (mode == AlertMode.SOUND && !recovery) "_alert_v2" else "_silent_v2"
}
@Suppress("UNUSED_PARAMETER")
internal fun shouldDisplayNotification(event: NotificationEvent, local: NotificationLocal, visibleChat: String?, sessions: List<dev.dshmobile.app.model.SessionSummary> = emptyList()) =
    event.sessionId != visibleChat && local.settings.allows(event) && notificationChannel(event, local) != null
/** A new completion is a new alert even if the prior answer in this chat was not dismissed. */
internal fun notificationUpdateSilent(recovery: Boolean, eventId: String, previousEventId: String?) =
    recovery || eventId == previousEventId

/** Exact Activity route visibility, memory only; an inactive UI never suppresses other chats. */
internal object VisibleNotificationChat {
    @Volatile var sessionId: String? = null
        set(value) {
            if (value != null) NotificationPosting.invalidateChat(value)
            field = value
        }
}
