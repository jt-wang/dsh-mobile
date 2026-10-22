package dev.dshmobile.app.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.dshmobile.app.MainActivity

internal const val NOTIFICATION_ACTIVITY_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
internal fun notificationChatListIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, 3,
    Intent(context, MainActivity::class.java).setAction("dev.dshmobile.OPEN_CHAT_LIST")
        .putExtra("notificationChatList", true).addFlags(NOTIFICATION_ACTIVITY_FLAGS),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
