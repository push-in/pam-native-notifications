package dev.pam.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Presentation extras of a rule (PushRenderingRule::color(), largeIcon(),
 * image() and the channel() options). Templates are rendered once when the
 * push arrives, so the stored conversation keeps concrete values.
 */
internal object PamNotificationStyle {
    fun resolve(style: JSONObject?, render: (String) -> String): JSONObject? {
        if (style == null || style.length() == 0) return null
        val resolved = JSONObject()
        style.optString("color").takeIf { COLOR.matches(it) }?.let { resolved.put("color", it) }
        style.optJSONObject("channel")?.let { resolved.put("channel", it) }
        for (field in listOf("largeIcon", "image")) {
            val value = style.optString(field).takeIf { it.isNotEmpty() }?.let(render)?.trim().orEmpty()
            if (value.isNotEmpty()) resolved.put(field, value.take(8_192))
        }
        return resolved.takeIf { it.length() > 0 }
    }

    fun color(style: JSONObject?): Int? =
        style?.optString("color")?.takeIf { COLOR.matches(it) }?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    /** Accent color and large icon; image loading stays on the push worker thread. */
    fun decorate(context: Context, builder: NotificationCompat.Builder, style: JSONObject?) {
        color(style)?.let(builder::setColor)
        style?.optString("largeIcon")?.takeIf { it.isNotEmpty() }
            ?.let { PamConversationNotifications.avatar(context, it) }
            ?.let(builder::setLargeIcon)
    }

    /** Creates the channel once with the rule's options (Android keeps the user's later changes). */
    fun ensureChannel(context: Context, id: String, name: String, importance: Int, style: JSONObject?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        val options = style?.optJSONObject("channel")
        manager.createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                options?.optString("description")?.takeIf { it.isNotBlank() }?.let { description = it.take(512) }
                if (options?.optBoolean("vibrate") == true) enableVibration(true)
                if (options?.optBoolean("lights") == true) {
                    enableLights(true)
                    color(style)?.let { lightColor = it }
                }
                if (options != null) lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
}
