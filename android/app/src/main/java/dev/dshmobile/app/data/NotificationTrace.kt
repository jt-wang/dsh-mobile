package dev.dshmobile.app.data

/** Debug-only fixed lifecycle/route labels. No IDs, tokens, addresses or question text. */
internal fun notificationTrace(event: String) {
    if (dev.dshmobile.app.BuildConfig.DEBUG) android.util.Log.d("DSHNotification", event)
}
