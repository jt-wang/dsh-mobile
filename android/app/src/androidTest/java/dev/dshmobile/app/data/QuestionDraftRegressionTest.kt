package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuestionDraftRegressionTest {
    private class Store : SecureStateStore {
        var text = "{}"
        override suspend fun read() = decodeStoredState(text)
        override suspend fun write(value: StoredState) { text = mobileJson.encodeToString(value) }
        override suspend fun clear() { text = "{}" }
    }
    @Test fun sameCallIdIsIsolatedAcrossSessionsAndRestoreAndClearedOnAnswerOrSkip() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = Store()
        MockWebServer().use { server ->
            fun session(id: String) = """{"id":"$id","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
            fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":true,"questionSkip":true,"approvals":false,"push":false}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[${session("a")},${session("b")}],"nextCursor":null}""")
                    "/v1/sessions/a", "/v1/sessions/b" -> {
                        val id = request.requestUrl!!.pathSegments.last()
                        json("""{"session":${session(id)},"messages":[],"cursor":1,"hasMore":false,"activity":"waiting","pendingQuestions":[{"callId":"same","state":"open","questions":[{"id":"q","question":"Synthetic?","header":"Checks","options":[{"label":"Yes"}],"multiSelect":false,"allowFreeText":true}]}]}""")
                    }
                    "/v1/sessions/a/answers", "/v1/sessions/b/answers" -> json("""{"requestId":"${JSONObject(request.body.readUtf8()).getString("requestId")}","status":"accepted","updatedAt":2}""")
                    else -> json("{}").setResponseCode(404)
                }
            }
            server.start()
            store.write(StoredState(host = PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")), "device", "synthetic-test-token-000000000000", "Synthetic"), selectedSessionId = "a"))
            var repo = NetworkMobileRepository(store, owner, true)
            val answerA = QuestionAnswer(listOf(AnswerItem("q", emptyList(), "Draft A")))
            val answerB = QuestionAnswer(listOf(AnswerItem("q", emptyList(), "Draft B")))
            try {
                repo.restore(); repo.setForeground(true)
                repo.saveAnswerDraft("a", "same", answerA)
                repo.selectSession("b")
                assertEquals("same", repo.state.value.snapshot!!.pendingQuestions.single().callId)
                assertNull(repo.state.value.answerDrafts[questionDraftKey("b", "same")])
                repo.saveAnswerDraft("b", "same", answerB)
                assertEquals(answerA, repo.state.value.answerDrafts[questionDraftKey("a", "same")])
                repo.close()
                repo = NetworkMobileRepository(store, owner, true)
                repo.restore(); repo.setForeground(true)
                assertEquals("b", repo.state.value.snapshot!!.session.id)
                assertEquals(answerB, repo.state.value.answerDrafts[questionDraftKey("b", "same")])
                repo.selectSession("a")
                assertEquals(answerA, repo.state.value.answerDrafts[questionDraftKey("a", "same")])
                repo.answer("same", answerA)
                assertNull(repo.state.value.error)
                assertNull(store.read().answerDrafts[questionDraftKey("a", "same")])
                assertEquals(answerB, store.read().answerDrafts[questionDraftKey("b", "same")])
                repo.selectSession("b"); repo.skipQuestion("same")
                assertNull(repo.state.value.error)
                assertTrue(store.read().answerDrafts.isEmpty())
                repo.close(); repo = NetworkMobileRepository(store, owner, true)
                repo.restore()
                assertTrue(repo.state.value.answerDrafts.isEmpty())
            } finally { repo.close(); owner.cancel() }
        }
    }
}
