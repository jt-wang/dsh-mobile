package dev.dshmobile.app.ui

import dev.dshmobile.app.model.ConnectionState
import dev.dshmobile.app.model.MobileState

internal fun chatNeedsSelection(target: String, state: MobileState, interactionBlocked: Boolean): Boolean =
    state.connection == ConnectionState.ONLINE && !state.busy && !interactionBlocked && state.error == null &&
        (state.snapshot?.session?.id != target || !state.observingSession)

internal fun notificationTargetSelected(target: String, state: MobileState): Boolean =
    state.snapshot?.session?.id == target && state.observingSession && !state.snapshotStale && !state.busy && state.error == null
