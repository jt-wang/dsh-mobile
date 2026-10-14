package dev.dshmobile.app.data

import org.junit.Assert.*
import org.junit.Test

class NotificationChannelsTest {
    private val event = NotificationEvent(1, "event", 1, 100, 1000, "project", "chat", "answer-finished", 1)
    @Test fun `channel follows each encrypted local preference independently`() {
        val local = NotificationLocal(answerMode = AlertMode.SOUND, attentionMode = AlertMode.SILENT)
        assertEquals("answers_alert_v2", notificationChannel(event, local))
        assertEquals("attention_silent_v2", notificationChannel(event.copy(kind = "attention-needed"), local))
        assertNull(notificationChannel(event, local.copy(answerMode = AlertMode.OFF)))
        assertEquals("answers_silent_v2", notificationChannel(event, local, recovery = true))
        assertEquals(local, mobileJson.decodeFromString<NotificationLocal>(mobileJson.encodeToString(NotificationLocal.serializer(), local)))
    }
    @Test fun `migration creates new stable channels once and deletes only legacy alerts`() {
        val calls = mutableListOf<String>()
        migrateNotificationChannels(emptySet(), create = { calls += "create:${it.id}:${it.alert}" }, delete = { calls += "delete:$it" })
        assertEquals(listOf("create:answers_alert_v2:true", "create:answers_silent_v2:false", "create:attention_alert_v2:true", "create:attention_silent_v2:false", "delete:answers", "delete:attention"), calls)
        calls.clear()
        migrateNotificationChannels(notificationChannels.map { it.id }.toSet(), create = { calls += it.id }, delete = { calls += "delete:$it" })
        assertEquals(listOf("delete:answers", "delete:attention"), calls)
    }
    @Test fun `open chat ingests without display while another chat can alert and updates stay silent`() {
        val local = NotificationLocal(settings = NotificationSettings(enabled = true))
        assertFalse(shouldDisplayNotification(event, local, "chat"))
        assertTrue(shouldDisplayNotification(event, local, "other"))
        assertFalse(shouldDisplayNotification(event, local.copy(answerMode = AlertMode.OFF), null))
        assertTrue(notificationUpdateSilent(recovery = false, alreadyShown = true))
        assertFalse(notificationUpdateSilent(recovery = false, alreadyShown = false))
        assertTrue(notificationUpdateSilent(recovery = true, alreadyShown = false))
        val events = listOf(event, event.copy(eventId = "second", sequence = 2))
        val applied = local.applyPage(NotificationPage(1, "epoch", events, "head", false, false, "ready"), 200)
        assertEquals(listOf("second"), applied.display.map { it.eventId })
        assertEquals(2, applied.state.processed.size)
        assertTrue(applied.state.applyPage(NotificationPage(1, "epoch", events.takeLast(1), "head", false, false, "ready"), 200).display.isEmpty())
    }
}
