package dev.dshmobile.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Child dismissal changes the collapsed summary destination as well as its count. */
class NotificationDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("removedTag")?.let { NotificationPosting.cancel(context, it, 2) }
    }
}
