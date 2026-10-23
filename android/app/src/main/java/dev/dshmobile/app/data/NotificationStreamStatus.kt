package dev.dshmobile.app.data

import dev.dshmobile.app.model.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** Live process state only: a persisted cursor/coverage is not proof of a connected stream. */
internal object NotificationStreamStatus {
    val status = MutableStateFlow<String?>(null)
}

/** Presentation only; never used for command admission or repository transport decisions. */
internal fun notificationHeaderConnection(connection: ConnectionState, stream: String?): ConnectionState =
    if (connection in setOf(ConnectionState.REVOKED, ConnectionState.INCOMPATIBLE)) connection else when (stream) {
        "offline", "reconnecting", "blocked" -> ConnectionState.OFFLINE
        "initializing" -> ConnectionState.CONNECTING
        "ready", "degraded" -> ConnectionState.ONLINE // Coverage is not transport liveness.
        else -> connection
    }
