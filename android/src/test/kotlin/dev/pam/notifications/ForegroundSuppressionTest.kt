package dev.pam.notifications

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSuppressionTest {
    @After
    fun background() = PamActiveRoute.setForeground(false)

    @Test
    fun foregroundSuppressionHonoursFieldAndBlankRecipientConditions() {
        val rules = JSONArray()
            .put(JSONObject().put("foreground", true).put("blank", JSONArray(listOf("user_id", "recipient_user_id"))))
            .put(JSONObject().put("foreground", true).put("where", JSONObject().put("system_event", "digest")))
        PamActiveRoute.setForeground(true)
        assertTrue(PamActiveRoute.matches(rules, mapOf("user_id" to " ")))
        assertFalse(PamActiveRoute.matches(rules, mapOf("user_id" to "", "recipient_user_id" to "42")))
        assertTrue(PamActiveRoute.matches(rules, mapOf("user_id" to "7", "system_event" to "digest")))
        assertFalse(PamActiveRoute.matches(rules, mapOf("user_id" to "7", "system_event" to "like")))
        assertTrue(PamActiveRoute.matches(JSONArray().put(JSONObject().put("foreground", true)), mapOf("user_id" to "7")))
        PamActiveRoute.setForeground(false)
        assertFalse(PamActiveRoute.matches(rules, emptyMap()))
    }
}
