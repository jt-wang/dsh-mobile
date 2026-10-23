package dev.dshmobile.app.data

import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationTapTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun differentNotificationsKeepImmutableTargets() {
        val a = notificationChatIntent(context, "device", "chat-a", null)
        val b = notificationChatIntent(context, "device", "chat-b", null)
        assertNotEquals(a, b)
        assertTrue(shadowOf(a).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadowOf(b).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals("chat-a", consumeNotificationIntent(shadowOf(a).savedIntent)?.chat)
        assertEquals("chat-b", consumeNotificationIntent(shadowOf(b).savedIntent)?.chat)
    }

    @Test fun consumedIntentCannotReapplyAfterRecreationAndNewIntentRoutesFromXToY() {
        val original = Intent(shadowOf(notificationChatIntent(context, "device", "x", null)).savedIntent)
        var route = consumeNotificationIntent(original)?.chat
        assertEquals("x", route)
        route = "manual-chat"
        val restored = Intent(original) // Activity's intent after consumption, not a saved target.
        consumeNotificationIntent(restored)?.chat?.let { route = it }
        assertEquals("manual-chat", route)
        val newIntent = Intent(shadowOf(notificationChatIntent(context, "device", "y", null)).savedIntent)
        consumeNotificationIntent(newIntent)?.chat?.let { route = it }
        assertEquals("y", route)
        assertNull(consumeNotificationIntent(newIntent))
        assertNull(original.data); assertNull(original.action)
        // Android may redeliver the original launch intent on process restoration; saved consumption wins.
        val systemRestored = Intent(shadowOf(notificationChatIntent(context, "device", "x", null)).savedIntent)
        assertNull(consumeNotificationIntent(systemRestored, restoredConsumed = true))
        assertFalse(systemRestored.hasExtra("notificationChat"))
    }

    @Test fun summaryIntentAlwaysTargetsList() {
        val target = consumeNotificationIntent(Intent(shadowOf(notificationChatListIntent(context)).savedIntent))!!
        assertTrue(target.chatList); assertNull(target.chat)
    }
}
