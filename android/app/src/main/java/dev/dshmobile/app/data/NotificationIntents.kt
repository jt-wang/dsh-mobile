package dev.dshmobile.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.dshmobile.app.MainActivity

internal data class NotificationIntentTarget(val device: String?, val chat: String?, val question: String?, val chatList: Boolean, val settings: Boolean)

internal fun notificationDeliveryIdentity(intent: Intent): String? = intent.getStringExtra("notificationDelivery")
    ?: intent.dataString ?: intent.getStringExtra("notificationChat")

/** Destructive one-shot admission: the Activity stores this cleared intent for recreation/recents. */
internal fun consumeNotificationIntent(intent: Intent, restoredConsumed: Boolean = false): NotificationIntentTarget? {
    val target = NotificationIntentTarget(intent.getStringExtra("notificationDevice"), intent.getStringExtra("notificationChat"),
        intent.getStringExtra("notificationQuestion"), intent.getBooleanExtra("notificationChatList", false), intent.getBooleanExtra("notificationSettings", false))
    if (target.chat == null && !target.chatList && !target.settings) return null
    listOf("notificationDevice", "notificationChat", "notificationQuestion", "notificationChatList", "notificationSettings", "notificationDelivery").forEach(intent::removeExtra)
    intent.data = null; intent.action = null
    return target.takeUnless { restoredConsumed }
}

internal fun notificationChatIntent(context: Context, device: String, chat: String, question: String?): PendingIntent {
    val data = android.net.Uri.Builder().scheme("dshmobile").authority("chat").appendPath(device).appendPath(chat).build()
    return PendingIntent.getActivity(context, data.toString().hashCode(), Intent(context, MainActivity::class.java)
        .setAction("dev.dshmobile.OPEN_CHAT").setData(data)
        .putExtra("notificationDelivery", java.util.UUID.randomUUID().toString())
        .putExtra("notificationDevice", device).putExtra("notificationChat", chat).putExtra("notificationQuestion", question)
        .addFlags(NOTIFICATION_ACTIVITY_FLAGS), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

internal const val NOTIFICATION_ACTIVITY_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
internal fun notificationChatListIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 3,
    Intent(context, MainActivity::class.java).setAction("dev.dshmobile.OPEN_CHAT_LIST")
        .putExtra("notificationChatList", true).putExtra("notificationDelivery", java.util.UUID.randomUUID().toString()).addFlags(NOTIFICATION_ACTIVITY_FLAGS),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
