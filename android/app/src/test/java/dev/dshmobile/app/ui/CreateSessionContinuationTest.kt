package dev.dshmobile.app.ui

import dev.dshmobile.app.data.*
import dev.dshmobile.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreateSessionContinuationTest {
    private class Repository : MobileRepository {
        private val old = SessionSummary("old", "Synthetic", "w", 1, false, true)
        override val state = MutableStateFlow(MobileState(paired = true, connection = ConnectionState.ONLINE,
            capabilities = MobileCapabilities(true, true, false, false, false, false, false, false),
            workspaces = listOf(Workspace("w", "Synthetic", true)), snapshot = SessionSnapshot(old, emptyList(), 1, false, "idle")))
        override val acceptedCreate = MutableStateFlow<CreateAcceptance?>(null)
        var immediate = false
        var request = ""
        val sends = mutableListOf<Pair<String, String>>()
        val drafts = mutableListOf<String>()
        override suspend fun createSession(workspaceId: String, presetId: String?, requestId: String) {
            request = requestId
            state.value = state.value.copy(pending = PendingCommand(request, "create", null, null, "pending"))
            if (immediate) accept()
        }
        fun accept() {
            acceptedCreate.value = CreateAcceptance(request, "returned")
            state.value = state.value.copy(pending = null, snapshot = SessionSnapshot(old.copy(id = "returned"), emptyList(), 2, false, "idle"))
        }
        fun reject() { state.value = state.value.copy(pending = null, error = "command_rejected") }
        override suspend fun updateDraft(text: String) { drafts += text; state.value = state.value.copy(draft = text) }
        override suspend fun sendMessage(text: String) {
            sends += state.value.snapshot!!.session.id to text
            state.value = state.value.copy(pending = PendingCommand("send", "send", "returned", text, "uncertain"))
        }
        override suspend fun restore() {}
        override suspend fun refresh() { state.value = state.value.copy(lastSyncedAt = (state.value.lastSyncedAt ?: 0) + 1) }
        override suspend fun resolvePending() { accept() }
        override suspend fun abandonPending() { state.value = state.value.copy(pending = null) }
        override suspend fun pair(invitationJson: String, deviceName: String) {}
        override suspend fun pair(invitation: Invitation, deviceName: String) {}
        override suspend fun selectSession(sessionId: String) {}
        override suspend fun cancelRun() {}
        override suspend fun forget() {}
        override suspend fun setForeground(active: Boolean) {}
        override fun close() {}
    }
    private fun checkFlow(immediate: Boolean = false, block: suspend TestScope.(MobileViewModel, Repository) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Repository().apply { this.immediate = immediate }
        val vm = MobileViewModel(RuntimeEnvironment.getApplication()) { repo }
        try {
            runCurrent()
            vm.editNewTask("Synthetic first task")
            vm.createSession("w", null, vm.newTaskText)
            runCurrent()
            block(vm, repo)
        } finally { androidx.lifecycle.ViewModelStore().apply { put("vm", vm); clear() }; Dispatchers.resetMain() }
    }
    @Test fun `pending then accepted sends exactly once to receipt session and moves form task`() = checkFlow { vm, repo ->
        assertTrue(repo.sends.isEmpty())
        repo.accept(); runCurrent()
        assertEquals(listOf("returned" to "Synthetic first task"), repo.sends)
        assertEquals(listOf("Synthetic first task"), repo.drafts)
        assertEquals("", vm.newTaskText)
        assertEquals("uncertain", vm.state.value.pending?.status)
        // Repeated receipt and refresh must never replay the already-started uncertain send.
        repo.accept(); vm.refresh(); runCurrent()
        assertEquals(1, repo.sends.size)
    }
    @Test fun `immediate accepted sends once`() = checkFlow(immediate = true) { vm, repo ->
        assertEquals(listOf("returned" to "Synthetic first task"), repo.sends)
        assertEquals("", vm.newTaskText)
    }
    @Test fun `pending rejected sends nothing even after stale acceptance`() = checkFlow { vm, repo ->
        repo.reject(); runCurrent(); repo.accept(); runCurrent()
        assertTrue(repo.sends.isEmpty()); assertEquals("Synthetic first task", vm.newTaskText)
    }
    @Test fun `abandoned create sends nothing even after late acceptance`() = checkFlow { vm, repo ->
        vm.abandonPending(); runCurrent(); repo.accept(); runCurrent()
        assertTrue(repo.sends.isEmpty())
    }
    @Test fun `changed task intent prevents late continuation`() = checkFlow { vm, repo ->
        vm.editNewTask("Replacement"); repo.accept(); runCurrent()
        assertTrue(repo.sends.isEmpty()); assertEquals("Replacement", vm.newTaskText)
    }
    @Test fun `unrelated acceptance and changed snapshot never prove create`() = checkFlow { _, repo ->
        repo.acceptedCreate.value = CreateAcceptance("other-request", "returned")
        repo.state.value = repo.state.value.copy(snapshot = repo.state.value.snapshot!!.copy(session = repo.state.value.snapshot!!.session.copy(id = "returned")))
        runCurrent(); assertTrue(repo.sends.isEmpty())
    }
}
