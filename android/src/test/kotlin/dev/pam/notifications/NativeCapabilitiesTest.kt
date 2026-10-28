package dev.pam.notifications

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilitiesTest {
    @Test
    fun replyActionIdsArePositiveTimeOrderedIntegers() {
        val first = ReplyFailure.createActionId(1_700_000_000_000).toLong()
        val later = ReplyFailure.createActionId(1_700_000_000_001).toLong()
        assertTrue(first in 1_700_000_000_000_000..1_700_000_000_000_999)
        assertTrue(later > first)
        assertTrue(later < 9_007_199_254_740_991)
        assertTrue(NotificationTemplate.complete("{action_id}", emptyMap()))
    }

    @Test
    fun notificationTemplatesResolveDataAndEncodeUrls() {
        val variables = mapOf("chat_id" to "42 a/b", "reply" to "Oi")
        assertEquals(
            "https://api.test/chats/42%20a%2Fb/messages",
            NotificationTemplate.render("https://api.test/chats/{chat_id}/messages", variables) {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            },
        )
        assertEquals("Oi ", NotificationTemplate.render("{reply} {missing}", variables))
        assertTrue(NotificationTemplate.complete("{chat_id}/{uuid}", variables))
        assertFalse(NotificationTemplate.complete("{unknown}", variables))
    }

    @Test
    fun credentialPlaceholdersResolveThePushAccountToken() {
        val tokens = mapOf("user-a" to "token-a", "user-b" to "token-b")
        val lookup: (String) -> String? = { tokens[PamNotificationCredentials.normalize(it)] }
        val pushA = mapOf("user_id" to " USER-A ", "recipient_user_id" to "user-b")
        assertEquals("user-a", PamNotificationCredentials.normalize(NotificationTemplate.credentialAccount("credential:user_id|recipient_user_id", pushA)))
        assertEquals(
            "Bearer token-a",
            NotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", pushA, null, lookup),
        )
        val legacy = mapOf("recipient_user_id" to "user-b")
        assertEquals(
            "Bearer token-b",
            NotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", legacy, null, lookup),
        )
        assertFalse(NotificationTemplate.missingCredential("Bearer {credential:user_id}", pushA, lookup))
        assertTrue(NotificationTemplate.missingCredential("Bearer {credential:user_id}", mapOf("user_id" to "user-c"), lookup))
        assertTrue(NotificationTemplate.missingCredential("Bearer {credential:user_id}", emptyMap(), lookup))
        assertFalse(NotificationTemplate.missingCredential("Bearer {storage:auth.token}", emptyMap(), lookup))
        assertEquals("Bearer ", NotificationTemplate.render("Bearer {credential:user_id}", mapOf("user_id" to "user-c"), null, lookup))
        assertTrue(NotificationTemplate.complete("Bearer {credential:user_id|recipient_user_id}", emptyMap()))
    }

    @Test
    fun credentialSlotsHashTheNormalizedAccount() {
        val slot = PamNotificationCredentials.slot(PamNotificationCredentials.normalize(" User-A "))
        assertEquals(64, slot.length)
        assertEquals(slot, PamNotificationCredentials.slot("user-a"))
        assertFalse(slot.contains("user"))
        assertFalse(slot == PamNotificationCredentials.slot("user-b"))
    }

    @Test
    fun pushTimestampsAcceptSecondsMillisecondsAndIso8601() {
        assertEquals(1_700_000_000_000, PamPushRendering.timestamp("1700000000"))
        assertEquals(1_700_000_000_000, PamPushRendering.timestamp("1700000000000"))
        assertEquals(1_700_000_000_500, PamPushRendering.timestamp("1700000000.5"))
        assertEquals(0L, PamPushRendering.timestamp("1970-01-01T00:00:00Z"))
    }

}
