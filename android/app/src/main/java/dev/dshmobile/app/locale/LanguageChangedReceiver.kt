package dev.dshmobile.app.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.dshmobile.app.data.refreshNotificationLanguage
import kotlinx.coroutines.launch

/** System locale changes also refresh channel labels while the Activity is closed. */
class LanguageChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED || intent.action == Intent.ACTION_APPLICATION_LOCALE_CHANGED) {
            val pending = goAsync()
            val refresh = refreshNotificationLanguage(context)
            dev.dshmobile.app.data.NotificationPosting.scope.launch {
                try { kotlinx.coroutines.withTimeoutOrNull(8_000) { refresh.join() } }
                finally { pending.finish() }
                // Larger sets continue on the bounded process-scoped pass after this broadcast window.
            }
        }
    }
}
