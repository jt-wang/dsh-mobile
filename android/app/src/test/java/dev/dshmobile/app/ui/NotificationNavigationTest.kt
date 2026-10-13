package dev.dshmobile.app.ui

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun intentIsNotConsumedUntilTargetSelectionFinishesSuccessfully() {
        val selected = MobileState(observingSession = true,
            snapshot = SessionSnapshot(SessionSummary("new", "Synthetic", "workspace", 1, false, true), emptyList(), 1, false, "idle"))
        assertFalse(notificationTargetSelected("old", selected))
        assertFalse(notificationTargetSelected("new", selected.copy(busy = true)))
        assertFalse(notificationTargetSelected("new", selected.copy(error = "network_unavailable")))
        assertFalse(notificationTargetSelected("new", selected.copy(observingSession = false)))
        assertFalse(notificationTargetSelected("new", selected.copy(snapshotStale = true)))
        assertTrue(notificationTargetSelected("new", selected))
    }
    @Test fun `last chat notification resumes observation even when its displayed snapshot matches`() {
        val snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic chat", "workspace", 1, false, true), emptyList(), 1, false, "idle")
        val home = MobileState(connection = ConnectionState.ONLINE, snapshot = snapshot, snapshotStale = true)
        assertTrue(chatNeedsSelection("chat", home, interactionBlocked = false))
        assertTrue(chatNeedsSelection("chat", home.copy(snapshotStale = false), interactionBlocked = false))
        assertFalse(chatNeedsSelection("chat", home.copy(observingSession = true), interactionBlocked = false))
        assertFalse(notificationTargetSelected("chat", home))
        assertFalse(chatNeedsSelection("chat", home.copy(busy = true), interactionBlocked = false))
        assertFalse(chatNeedsSelection("chat", home, interactionBlocked = true))
        assertFalse(chatNeedsSelection("chat", home.copy(error = "network_unavailable"), interactionBlocked = false))
        assertFalse(chatNeedsSelection("chat", home.copy(connection = ConnectionState.OFFLINE), interactionBlocked = false))
    }
}
