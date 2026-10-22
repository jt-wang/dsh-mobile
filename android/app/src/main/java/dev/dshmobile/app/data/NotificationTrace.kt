package dev.dshmobile.app.data

/** Debug-only fixed lifecycle/route labels. No IDs, tokens, addresses or question text. */
internal fun notificationTrace(event: String) {
    if (dev.dshmobile.app.BuildConfig.DEBUG) android.util.Log.d("DSHNotification", event)
}

/** Hash only the random journal event ID, never a session/device identity or content. */
internal fun notificationEventTrace(stage: String, eventId: String, sequence: Long) {
    if (!dev.dshmobile.app.BuildConfig.DEBUG) return
    val correlation = java.security.MessageDigest.getInstance("SHA-256").digest(eventId.toByteArray())
        .take(6).joinToString("") { "%02x".format(it) }
    notificationTrace("$stage event=$correlation seq=$sequence")
}
