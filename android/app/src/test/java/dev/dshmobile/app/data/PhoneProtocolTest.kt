package dev.dshmobile.app.data

import dev.dshmobile.app.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhoneProtocolTest {
    @Test fun `session waiting flag defaults false and updates from authoritative snapshots`() {
        val json = """{"id":"chat","title":"","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true}"""
        val old = decodeWire<SessionSummary>(json).checked()
        assertFalse(old.waitingForAnswer); assertNull(old.blank); assertEquals("", old.title)
        val waiting = decodeWire<SessionSummary>(json.dropLast(1) + """, "waitingForAnswer":true,"blank":true}""").checked()
        assertTrue(waiting.waitingForAnswer); assertEquals(true, waiting.blank)
        val state = MobileState(sessions = listOf(old))
        val pending = SessionSnapshot(waiting, emptyList(), 1, false, "waiting")
        val replaced = MobileReducer.replaceSnapshot(state, pending, 1)
        assertTrue(replaced.sessions.single().waitingForAnswer)
        assertFalse(MobileReducer.replaceSnapshot(replaced, pending.copy(session = old), 2).sessions.single().waitingForAnswer)
    }

    @Test fun `old host omits additive capabilities and snapshot fields`() {
        val caps = decodeWire<CapabilitiesResponse>("""{"protocolVersion":1,"hostName":"Synthetic","upstreamVersion":"old","capabilities":{"sessions":true,"textPrompt":true,"cancel":true,"liveSnapshots":true,"attachments":false,"questions":false,"approvals":false,"push":false}}""")
        assertFalse(caps.capabilities.steer); assertFalse(caps.capabilities.queue)
        assertFalse(caps.capabilities.rename); assertFalse(caps.capabilities.models)
        assertNull(caps.hostVersion)
    }
    @Test fun `snapshot parses queue questions and assistant attachments`() {
        val snapshot = decodeWire<SessionSnapshot>("""{"session":{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":true,"canExecute":true},"messages":[{"id":"m","role":"assistant","text":"","createdAt":1,"attachments":[{"id":"img","name":"a.png","size":10,"mime":"image/png","kind":"image"}]}],"cursor":1,"hasMore":false,"activity":"waiting","pendingQueue":[{"id":"queued","text":"Later","delivery":"queue"}],"pendingQuestions":[{"callId":"call","state":"continued","questions":[{"id":"q","question":"Choose","options":[{"label":"A"}],"multiSelect":false,"allowFreeText":true}]}]}""").checked("chat")
        assertEquals("image", snapshot.messages.single().attachments.single().kind)
        assertEquals("queued", snapshot.pendingQueue.single().id)
        assertEquals("A", snapshot.pendingQuestions.single().questions.single().options.single().label)
    }
    @Test fun `message serialization preserves old payload and adds explicit steering or uploads only when present`() {
        assertEquals("""{"requestId":"r","text":"hello"}""", mobileJson.encodeToString(MessageBody("r", "hello")))
        assertEquals("""{"requestId":"r","text":"","delivery":"steer","uploadIds":["u"]}""", mobileJson.encodeToString(MessageBody("r", "", "steer", listOf("u"))))
    }
    @Test fun `answer validates exact labels single multi custom and duplicate IDs`() {
        val q = QuestionItem("q", question = "Choose", options = listOf(QuestionOption("A"), QuestionOption("B")), multiSelect = false, allowFreeText = true)
        val pending = PendingQuestion("call", "continued", listOf(q))
        assertTrue(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", listOf("A"))))))
        assertFalse(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", listOf("A", "B"))))))
        assertFalse(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", listOf("unknown"))))))
        assertFalse(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", emptyList())))))
        assertTrue(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", emptyList(), "Custom")))))
        assertTrue(PhoneRules.validAnswer(pending.copy(questions = listOf(q.copy(multiSelect = true))), QuestionAnswer(listOf(AnswerItem("q", listOf("A", "B"))))))
        assertFalse(PhoneRules.validAnswer(pending, QuestionAnswer(listOf(AnswerItem("q", listOf("A", "A"))))))
    }
    @Test fun `snapshot new fields preserve model recommendation delivery and resolved answers`() {
        val snapshot = decodeWire<SessionSnapshot>("""{"session":{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":true,"canExecute":true},"model":{"provider":"fixture","id":"vision"},"messages":[{"id":"m","role":"user","text":"Synthetic","createdAt":1,"delivery":"steer"}],"cursor":1,"hasMore":false,"activity":"idle","resolvedQuestions":[{"callId":"call","outcome":"skipped","answers":[]}]}""").checked("chat")
        assertEquals("vision", snapshot.model?.id); assertEquals("steer", snapshot.messages.single().delivery)
        assertEquals("skipped", snapshot.resolvedQuestions!!.single().outcome)
        assertEquals("""{"requestId":"r","callId":"call","skip":true}""", mobileJson.encodeToString(SkipBody("r", "call", true)))
        val option = decodeWire<QuestionOption>("""{"label":"A (Recommended)","recommended":true}""")
        assertEquals("A (Recommended)", option.label); assertTrue(option.recommended)
    }
    @Test fun `upload limits apply to all files and normalize MIME`() {
        assertTrue(PhoneRules.canAddAttachment(List(19) { 1L }, 20L * 1024 * 1024))
        assertFalse(PhoneRules.canAddAttachment(List(20) { 1L }, 1))
        assertFalse(PhoneRules.canAddAttachment(emptyList(), 20L * 1024 * 1024 + 1))
        assertFalse(PhoneRules.canAddAttachment(List(10) { 20L * 1024 * 1024 }, 1))
        assertFalse(PhoneRules.canAddAttachment(emptyList(), 0))
        assertEquals("application/octet-stream", PhoneRules.uploadMime("image/heic"))
        assertEquals("image/png", PhoneRules.uploadMime("image/png"))
    }
    @Test fun `history accepts zero large and 100 attachments while dropping individual malformed refs`() {
        val refs = (0..99).joinToString(",") { """{"id":"file$it","name":"","size":${if (it == 0) 0 else 999999999},"mime":"application/octet-stream","kind":"file"}""" }
        val snapshot = decodeWire<SessionSnapshot>("""{"session":{"id":"chat","title":"Synthetic","workspaceId":"w","updatedAt":1,"running":false,"canExecute":true},"messages":[{"id":"m","role":"user","text":"keep","createdAt":1,"attachments":[$refs,{"id":false}]}],"cursor":1,"hasMore":false,"activity":"idle"}""").checked("chat")
        assertEquals(100, snapshot.messages.single().attachments.size)
        assertEquals(0L, snapshot.messages.single().attachments.first().size)
        val bad = snapshot.copy(messages = listOf(snapshot.messages.single().copy(attachments = listOf(AttachmentRef("bad", "bad", -1, "text/plain", "file"), snapshot.messages.single().attachments.last())))).checked("chat")
        assertEquals(1, bad.messages.single().attachments.size)
        assertEquals("keep", bad.messages.single().text)
    }
    @Test fun `custom input limit counts code points UTF8 without splitting emoji`() {
        val limited = PhoneRules.boundedAnswerText("😀".repeat(9000))
        assertEquals(32768, limited.toByteArray().size)
        assertEquals(8192, limited.codePointCount(0, limited.length))
        assertEquals("ab", PhoneRules.boundedAnswerText("a\u0000b"))
    }
    @Test fun `malformed individual draft entries cannot lose the paired state`() {
        val state = decodeStoredState("""{"drafts":{"good":"keep","bad":false},"attachmentDrafts":{"bad":[{"size":false}]},"answerDraftsByQuestion":{"bad":{"answers":false},"4:chatgood":{"answers":[{"id":"q","selected":[],"custom":"keep"}]}}}""")
        assertEquals(mapOf("good" to "keep"), state.drafts)
        assertEquals(setOf(questionDraftKey("chat", "good")), state.answerDrafts.keys)
        assertTrue(state.attachmentDrafts.isEmpty())
    }
    @Test fun `legacy callId only answers are discarded without touching other state`() {
        val state = decodeStoredState("""{"drafts":{"chat":"keep"},"answerDrafts":{"call":{"answers":[{"id":"q","selected":["Yes"]}]}}}""")
        assertTrue(state.answerDrafts.isEmpty())
        assertEquals("keep", state.drafts["chat"])
        assertNotEquals(questionDraftKey("a:b", "c"), questionDraftKey("a", "b:c"))
    }
    @Test fun `optional phone stored fields are independently tolerant`() {
        val command = """{"requestId":"00000000-0000-4000-8000-000000000001","kind":"send","sessionId":"chat","text":"keep"}"""
        for (field in listOf("attachmentDrafts", "answerDrafts", "answerDraftsByQuestion", "possiblyUsedUploads", "uploadProtectionUntil")) {
            for (bad in listOf("null", "false", "[]", "\"bad\"", "{\"broken\":false}")) {
                val state = decodeStoredState("""{"selectedSessionId":"chat","drafts":{"chat":"keep"},"pending":$command,"$field":$bad}""")
                assertEquals("keep", state.pending?.text)
                assertEquals("keep", state.drafts["chat"])
            }
        }
        for (field in listOf("delivery", "uploadIds", "itemId", "action", "title", "selection", "callId", "answer", "skip", "attachmentSources")) {
            for (bad in listOf("null", "false", "[]", "\"bad\"", "{\"broken\":false}")) {
                val damaged = command.dropLast(1) + """, "$field":$bad}"""
                val state = decodeStoredState("""{"pending":$damaged,"acceptedPrompts":[$damaged]}""")
                assertEquals("keep", state.pending?.text)
                assertEquals("keep", state.acceptedPrompts.single().text)
            }
        }
    }
    @Test fun `protection sanitizes direct store restores as well as decoded records`() {
        val state = StoredState(possiblyUsedUploads = (1..1300).associate { "id-$it" to 9999999999999L } + ("expired" to 1L)).sanitizeDrafts(now = 1000)
        assertEquals(1280, state.possiblyUsedUploads.size)
        assertFalse(state.possiblyUsedUploads.containsKey("expired"))
        assertEquals(601000L, state.uploadProtectionUntil)
    }
    @Test fun `title limit counts UTF8 bytes and rejects controls`() {
        assertTrue(PhoneRules.validTitle("я".repeat(128)))
        assertFalse(PhoneRules.validTitle("я".repeat(129)))
        assertFalse(PhoneRules.validTitle("bad\ntitle")); assertFalse(PhoneRules.validTitle("  "))
    }
}
