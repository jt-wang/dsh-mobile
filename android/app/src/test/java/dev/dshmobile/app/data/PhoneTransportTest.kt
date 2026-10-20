package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PhoneTransportTest {
    private fun api(server: MockWebServer) = HostApi(HostEndpoint(server.url("/").toString(), null), "synthetic-device-token")
    @Test fun `upload streams exact length unicode filename and bearer then image GET is bounded and authorized`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setResponseCode(201).setBody("""{"uploadId":"upload","expiresAt":1000,"attachment":{"id":"image","name":"фото.png","size":3,"mime":"image/png","kind":"image"}}"""))
                val receipt = api.upload("chat", "фото.png", "image/png", 3, { byteArrayOf(1,2,3).inputStream() }, {})
                assertEquals("upload", receipt.uploadId)
                val request = server.takeRequest(); assertEquals("/v1/sessions/chat/uploads", request.path)
                assertEquals("3", request.getHeader("Content-Length")); assertNull(request.getHeader("Transfer-Encoding"))
                assertEquals("%D1%84%D0%BE%D1%82%D0%BE.png", request.getHeader("X-File-Name")); assertEquals("Bearer synthetic-device-token", request.getHeader("Authorization")); assertEquals(3L, request.bodySize)
                server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(okio.Buffer().write(byteArrayOf(1,2,3))))
                assertArrayEquals(byteArrayOf(1,2,3), api.image("chat", "image")); assertEquals("/v1/sessions/chat/attachments/image", server.takeRequest().path)
            } finally { api.close() }
        }
    }
    @Test fun `discard upload accepts empty 204 without JSON content type`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setResponseCode(204))
                api.discardUpload("chat", "upload")
                val request = server.takeRequest()
                assertEquals("DELETE", request.method)
                assertEquals("/v1/sessions/chat/uploads/upload", request.path)
                assertEquals(1, server.requestCount)
            } finally { api.close() }
        }
    }
    @Test fun `file download uses read bearer route bounded bytes and rejects non binary`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("Synthetic"))
                assertEquals("Synthetic", api.file("chat", "file").toString(Charsets.UTF_8))
                val request = server.takeRequest(); assertEquals("/v1/sessions/chat/files/file", request.path); assertEquals("Bearer synthetic-device-token", request.getHeader("Authorization"))
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("Synthetic"))
                try { api.file("chat", "file"); fail("HTML accepted") } catch (failure: MobileFailure) { assertEquals("chat_unavailable", failure.key) }
            } finally { api.close() }
        }
    }
    @Test fun `new mutations use exact durable request id and route without replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val api = api(server)
            try {
                val commands = listOf(
                    StoredCommand(UUID.randomUUID().toString(), "queue", sessionId = "chat", itemId = "item", action = "remove") to "queue",
                    StoredCommand(UUID.randomUUID().toString(), "rename", sessionId = "chat", title = "Synthetic") to "title",
                    StoredCommand(UUID.randomUUID().toString(), "model", sessionId = "chat", selection = ModelSelection("fixture", "model")) to "model",
                    StoredCommand(UUID.randomUUID().toString(), "answer", sessionId = "chat", callId = "call", answer = QuestionAnswer(listOf(AnswerItem("q", listOf("Yes"))))) to "answers")
                commands.forEach { (command, route) ->
                    server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody("""{"requestId":"${command.requestId}","status":"accepted","updatedAt":1}"""))
                    assertEquals("accepted", api.command(command).status)
                    val request = server.takeRequest(); assertEquals("/v1/sessions/chat/$route", request.path); assertTrue(request.body.readUtf8().contains(command.requestId))
                }
                assertEquals(4, server.requestCount)
            } finally { api.close() }
        }
    }
}
