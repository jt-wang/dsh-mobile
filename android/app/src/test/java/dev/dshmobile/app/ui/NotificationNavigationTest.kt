package dev.dshmobile.app.ui

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

class NotificationNavigationTest {
    @Test fun lateValidationAndCompletionCannotReplaceANewerTap() {
        val requests = NotificationTargetRequests()
        val a = requests.next(); val b = requests.next()
        assertFalse(requests.isCurrent(a)); assertTrue(requests.isCurrent(b))
        requests.next() // Chat list or Back invalidates both pending targets.
        assertFalse(requests.isCurrent(b))
    }
    @Test fun pendingTargetIsReadOnlyBeforeFreshSelection() {
        val selected = MobileState(snapshot = SessionSnapshot(SessionSummary("old", "Old", "w", 1, false, true), emptyList(), 1, false, "idle"))
        val loading = notificationRouteState("new", selected, true)
        assertNull(loading.snapshot); assertTrue(loading.snapshotStale); assertTrue(loading.busy)
        assertFalse(dev.dshmobile.app.model.PhoneRules.canMutate(loading))
        val stale = notificationRouteState("old", selected, true)
        assertNotNull(stale.snapshot); assertTrue(stale.snapshotStale)
    }
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
