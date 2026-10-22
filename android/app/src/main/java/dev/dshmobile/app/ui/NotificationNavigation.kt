package dev.dshmobile.app.ui

import dev.dshmobile.app.model.ConnectionState
import dev.dshmobile.app.model.MobileState

internal fun chatNeedsSelection(target: String, state: MobileState, interactionBlocked: Boolean): Boolean =
    state.connection == ConnectionState.ONLINE && !state.busy && !interactionBlocked && state.error == null &&
        (state.snapshot?.session?.id != target || !state.observingSession)

internal fun notificationTargetSelected(target: String, state: MobileState): Boolean =
    state.snapshot?.session?.id == target && state.observingSession && !state.snapshotStale && !state.busy && state.error == null

/** Local intent validation and read completions share a latest-request generation. */
internal class NotificationTargetRequests {
    private var revision = 0L
    fun next(): Long = ++revision
    fun isCurrent(value: Long): Boolean = revision == value
}

/** Route presentation is independent of network admission; never expose the old chat as the target. */
internal fun notificationRouteState(target: String, state: MobileState, pending: Boolean): MobileState {
    val snapshot = state.snapshot?.takeIf { it.session.id == target }
    return state.copy(snapshot = snapshot, snapshotStale = state.snapshotStale || pending,
        busy = state.busy || (pending && state.error == null && (snapshot == null || !state.observingSession)))
}
