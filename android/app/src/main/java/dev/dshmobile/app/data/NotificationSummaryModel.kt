package dev.dshmobile.app.data

internal data class NotificationAlertIdentity(val device: String, val chat: String, val kind: String)
internal enum class NotificationSummaryKind { ANSWERS, ATTENTION, MIXED }
internal data class NotificationSummary(val chats: Int, val answers: Int, val attention: Int) {
    val kind: NotificationSummaryKind get() = when {
        answers == 0 -> NotificationSummaryKind.ATTENTION
        attention == 0 -> NotificationSummaryKind.ANSWERS
        else -> NotificationSummaryKind.MIXED
    }
}
/** Counts current alert-bearing chats, not unread messages or child notification records. */
internal fun notificationSummary(alerts: List<NotificationAlertIdentity>) = NotificationSummary(
    alerts.distinctBy { it.device to it.chat }.size,
    alerts.filter { it.kind == "answers" }.distinctBy { it.device to it.chat }.size,
    alerts.filter { it.kind == "attention" }.distinctBy { it.device to it.chat }.size,
)
