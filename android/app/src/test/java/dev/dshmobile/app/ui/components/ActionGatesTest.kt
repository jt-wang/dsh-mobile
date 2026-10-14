package dev.dshmobile.app.ui.components

import dev.dshmobile.app.model.*
import org.junit.Assert.*
import org.junit.Test

class ActionGatesTest {
    private fun state(running: Boolean = true, activity: String = "running") = MobileState(
        paired = true, connection = ConnectionState.ONLINE,
        capabilities = MobileCapabilities(true, true, true, true, false, false, false, false),
        workspaces = listOf(Workspace("project", "Synthetic project", true)),
        snapshot = SessionSnapshot(SessionSummary("chat", "Synthetic chat", "project", 1, running, true), emptyList(), 1, false, activity),
    )

    @Test fun `stop requires both authoritative running flag and supported activity`() {
        assertTrue(canCancel(state()))
        assertFalse(canCancel(state(running = false)))
        assertFalse(canCancel(state(activity = "waiting")))
        assertFalse(canCancel(state(activity = "unknown")))
        assertFalse(canCancel(state().copy(snapshotStale = true)))
    }

    @Test fun `subagent only working never enables main turn Stop`() {
        val idle = state(running = false, activity = "idle")
        assertFalse(canCancel(idle.copy(snapshot = idle.snapshot!!.copy(session = idle.snapshot.session.copy(runningSubagents = 3)))))
        val main = state()
        assertTrue(canCancel(main.copy(snapshot = main.snapshot!!.copy(session = main.snapshot.session.copy(runningSubagents = 3)))))
    }
    @Test fun `mutation gates remain closed offline busy and unresolved`() {
        for (blocked in listOf(state().copy(connection = ConnectionState.OFFLINE), state().copy(busy = true),
            state().copy(pending = PendingCommand("request", "create", null, null, "uncertain")))) {
            assertFalse(canCreate(blocked)); assertFalse(canCancel(blocked))
        }
        assertTrue(canCreate(state()))
    }

    @Test fun `stale and offline history allow draft editing but never send`() {
        for (editable in listOf(state().copy(snapshotStale = true),
            state().copy(connection = ConnectionState.OFFLINE, snapshotStale = true))) {
            assertTrue(canEditDraft(editable))
            assertFalse(MobileReducer.canSend(editable, "Local draft"))
            assertTrue(canEditDraft(editable, interactionBlocked = true))
            assertTrue(canEditDraft(editable.copy(busy = true)))
            assertFalse(canEditDraft(editable.copy(pending = PendingCommand("request", "send", "chat", "text", "uncertain"))))
        }
        assertFalse(canEditDraft(state().copy(workspaces = listOf(Workspace("project", "Synthetic", false)))))
        assertFalse(canEditDraft(state().copy(capabilities = state().capabilities!!.copy(textPrompt = false))))
    }
    @Test fun `read only project or host capability blocks execution`() {
        val readOnly = state().copy(workspaces = listOf(Workspace("project", "Synthetic project", false)))
        assertFalse(executable(readOnly)); assertFalse(canCreate(readOnly)); assertFalse(canCancel(readOnly))
        assertFalse(canCreate(state().copy(capabilities = state().capabilities!!.copy(sessions = false))))
        assertFalse(canCancel(state().copy(capabilities = state().capabilities!!.copy(cancel = false))))
    }
}
