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
    @Test fun `language refresh renames all existing channels including connection without migrating or creating missing channels`() {
        val names = mutableMapOf<String, String>()
        val existing = setOf("answers_alert_v2", "attention_silent_v2", "connection", "unrelated")
        refreshChannelNames(existing, "Agent answers", "Your decision needed", "With sound", "Silent", "Connection to computer") { id, name -> names[id] = name }
        assertEquals(mapOf("answers_alert_v2" to "Agent answers · With sound", "attention_silent_v2" to "Your decision needed · Silent", "connection" to "Connection to computer"), names)
        refreshChannelNames(existing, "Ответы агента", "Нужно ваше решение", "Со звуком", "Без звука", "Связь с компьютером") { id, name -> names[id] = name }
        assertEquals("Ответы агента · Со звуком", names["answers_alert_v2"])
        assertEquals("Нужно ваше решение · Без звука", names["attention_silent_v2"])
        assertEquals("Связь с компьютером", names["connection"])
        assertEquals(3, names.size)
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
