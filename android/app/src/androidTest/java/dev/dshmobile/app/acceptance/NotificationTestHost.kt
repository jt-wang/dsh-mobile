package dev.dshmobile.app.acceptance

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshmobile.app.MainActivity
import dev.dshmobile.app.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject

/** Every journey owns a fresh feed epoch/question; no shared fixture mutation or visible Activity. */
internal class NotificationTestHost : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = MockWebServer()
    @Volatile private var resolved = false
    init {
        cleanup()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val session = """{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":true,"canExecute":true}"""
                val question = """{"callId":"question","state":"open","questions":[{"id":"q","question":"Which synthetic check?","options":[{"label":"Unit tests"}],"multiSelect":true}]}"""
                fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
                return when (request.requestUrl!!.encodedPath) {
                    "/v1/capabilities" -> json("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"fixture","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":false,"attachments":false,"questions":true,"approvals":false,"push":false,"notifications":true}}""")
                    "/v1/workspaces" -> json("""{"items":[{"id":"w","name":"Synthetic","canExecute":true}]}""")
                    "/v1/presets" -> json("""{"items":[]}""")
                    "/v1/sessions" -> json("""{"items":[$session],"nextCursor":null}""")
                    "/v1/sessions/chat" -> json("""{"session":$session,"messages":[],"cursor":1,"hasMore":false,"activity":"waiting","pendingQuestions":${if (resolved) "[]" else "[$question]"},"resolvedQuestions":${if (resolved) """[{"callId":"question","outcome":"answered","answers":[{"id":"q","selected":["Unit tests"]}]}]""" else "[]"}}""")
                    "/v1/sessions/chat/answers" -> { resolved = true; json("""{"requestId":"${JSONObject(request.body.readUtf8()).getString("requestId")}","status":"accepted","updatedAt":20}""") }
                    "/v1/notification-settings" -> json("""{"revision":1,"enabled":true,"projects":[],"chats":[]}""")
                    "/v1/notification-events/stream" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: notification-page\ndata: {\"version\":1,\"epoch\":\"synthetic\",\"items\":[],\"nextCursor\":\"cursor\",\"hasMore\":false,\"resetRequired\":true,\"coverage\":\"ready\",\"pending\":[{\"sessionId\":\"chat\",\"workspaceId\":\"w\",\"attentionId\":\"attention\"}]}\n\n").setSocketPolicy(SocketPolicy.KEEP_OPEN)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        runBlocking {
            EncryptedStateStore(context).write(StoredState(host = PairedHost(HostEndpoint(server.url("/").toString().removeSuffix("/")), "synthetic", "synthetic-notification-token-00000000", "Synthetic")))
            NotificationStore(context).update { NotificationLocal(enabled = true, deviceId = "synthetic", settings = NotificationSettings(enabled = true), dirty = true, channelModesMigrated = true) }
        }
    }
    private fun cleanup() {
        runBlocking { NotificationStore(context).update { it.copy(enabled = false) } }
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it).toList() }.filterIsInstance<MainActivity>().forEach { it.finishAndRemoveTask() }
            context.stopService(Intent(context, NotificationService::class.java))
        }
        instrumentation.waitForIdleSync()
        VisibleNotificationChat.sessionId = null
        NotificationPosting.cancelAll(context)
    }
    override fun close() { cleanup(); server.shutdown() }
}
