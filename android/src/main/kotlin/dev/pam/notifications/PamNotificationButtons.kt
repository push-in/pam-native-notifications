package dev.pam.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Buttons of a rule (PushRenderingRule::action() and ::openAction()) on a
 * standard or a conversation notification.
 *
 * - Background buttons send their endpoint natively (PamNotificationActionReceiver),
 *   app killed or not, and report a Button action.
 * - Open buttons start the app like a tap on the notification, with the
 *   button's extra data merged into the push data and its own deep link, so
 *   the app's `Opened` handler can tell them apart (e.g. accept an invite).
 */
internal object PamNotificationButtons {
    const val EXTRA_DISMISS_CONVERSATION = "pam.notification.dismiss.conversation"
    const val EXTRA_DISMISS_TAG = "pam.notification.dismiss.tag"
    const val EXTRA_DISMISS_ID = "pam.notification.dismiss.id"
    const val EXTRA_OPEN_BUTTON = "pam.notification.open.button"
    const val EXTRA_OPEN_ENDPOINT = "pam.notification.open.endpoint"
    const val MAX_BUTTONS = 3

    /** Where the buttons live: the posted notification and the push behind it. */
    data class Target(
        val eventId: String,
        val title: String,
        val body: String,
        val dataJson: String,
        val deepLink: String,
        val tag: String?,
        val notificationId: Int,
        /** Conversation key: dismissing also forgets the stored history. */
        val conversation: String = "",
    )

    /**
     * Renders the push-time templates of open buttons (data values, deep link);
     * background endpoints keep theirs, rendered when tapped.
     */
    fun resolve(buttons: JSONArray?, render: (String) -> String): JSONArray? {
        if (buttons == null || buttons.length() == 0) return null
        val resolved = JSONArray()
        for (index in 0 until minOf(buttons.length(), MAX_BUTTONS)) {
            val button = buttons.optJSONObject(index) ?: continue
            val copy = JSONObject(button.toString())
            if (button.optBoolean("open")) {
                val data = button.optJSONObject("data") ?: JSONObject()
                copy.put("data", JSONObject().apply { data.keys().forEach { key -> put(key, render(data.optString(key))) } })
                copy.put("deepLink", render(button.optString("deepLink")))
            }
            resolved.put(copy)
        }
        return resolved.takeIf { it.length() > 0 }
    }

    fun add(context: Context, builder: NotificationCompat.Builder, buttons: JSONArray?, target: Target) {
        if (buttons == null) return
        for (index in 0 until minOf(buttons.length(), MAX_BUTTONS)) {
            val button = buttons.optJSONObject(index) ?: continue
            val label = button.optString("label")
            if (label.isBlank()) continue
            val action = if (button.optBoolean("open")) {
                val pending = openIntent(context, button, target, index) ?: continue
                NotificationCompat.Action.Builder(0, label, pending).setShowsUserInterface(true).build()
            } else {
                val endpoint = button.optJSONObject("endpoint") ?: continue
                NotificationCompat.Action.Builder(0, label, backgroundIntent(context, button, endpoint, target, index))
                    .setShowsUserInterface(false)
                    .build()
            }
            builder.addAction(action)
        }
    }

    private fun backgroundIntent(context: Context, button: JSONObject, endpoint: JSONObject, target: Target, index: Int): PendingIntent {
        val intent = Intent(context, PamNotificationActionReceiver::class.java)
            .setAction(PamConversationNotifications.ACTION_BUTTON)
            .setPackage(context.packageName)
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_ID, button.optString("id"))
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_ENDPOINT, endpoint.toString())
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_DATA, target.dataJson)
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_DEEP_LINK, target.deepLink)
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_TAG, target.tag.orEmpty())
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_NOTIFICATION, target.notificationId)
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_DISMISS, button.optBoolean("dismiss", true))
            .putExtra(PamConversationNotifications.EXTRA_BUTTON_CONVERSATION, target.conversation)
        return PendingIntent.getBroadcast(
            context,
            requestCode(target, 16 + index),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openIntent(context: Context, button: JSONObject, target: Target, index: Int): PendingIntent? {
        val data = runCatching { JSONObject(target.dataJson) }.getOrDefault(JSONObject())
        button.optJSONObject("data")?.let { extra -> extra.keys().forEach { key -> data.put(key, extra.optString(key)) } }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("pam.notification.opened", true)
            .putExtra("pam.notification.id", target.eventId)
            .putExtra("pam.notification.title", target.title)
            .putExtra("pam.notification.body", target.body)
            .putExtra("pam.notification.data", data.toString())
            .putExtra("pam.notification.deepLink", button.optString("deepLink").ifBlank { target.deepLink })
        button.optJSONObject("endpoint")?.let { endpoint ->
            launch.putExtra(EXTRA_OPEN_BUTTON, button.optString("id")).putExtra(EXTRA_OPEN_ENDPOINT, endpoint.toString())
        }
        if (button.optBoolean("dismiss", true)) {
            if (target.conversation.isNotEmpty()) {
                launch.putExtra(EXTRA_DISMISS_CONVERSATION, target.conversation)
            } else {
                launch.putExtra(EXTRA_DISMISS_TAG, target.tag.orEmpty()).putExtra(EXTRA_DISMISS_ID, target.notificationId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode(target, 32 + index),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The app received an open-button tap: remove its notification and send
     * the button's endpoint natively (reported as a Button action).
     */
    fun handleOpen(context: Context, intent: Intent) {
        val conversation = intent.getStringExtra(EXTRA_DISMISS_CONVERSATION).orEmpty()
        when {
            conversation.isNotEmpty() -> PamConversationNotifications.cancel(context, conversation)
            intent.hasExtra(EXTRA_DISMISS_ID) -> NotificationManagerCompat.from(context)
                .cancel(intent.getStringExtra(EXTRA_DISMISS_TAG)?.ifEmpty { null }, intent.getIntExtra(EXTRA_DISMISS_ID, 0))
        }
        val endpoint = intent.getStringExtra(EXTRA_OPEN_ENDPOINT)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (endpoint != null) {
            val app = context.applicationContext
            val id = intent.getStringExtra(EXTRA_OPEN_BUTTON).orEmpty()
            val data = intent.getStringExtra("pam.notification.data").orEmpty().ifEmpty { "{}" }
            val deepLink = intent.getStringExtra("pam.notification.deepLink").orEmpty()
            kotlin.concurrent.thread(name = "pam-notification-open-button") {
                PamNotificationActions.handleButton(app, id, endpoint, data, deepLink, "", 0, dismiss = false)
            }
        }
        listOf(EXTRA_DISMISS_CONVERSATION, EXTRA_DISMISS_TAG, EXTRA_DISMISS_ID, EXTRA_OPEN_BUTTON, EXTRA_OPEN_ENDPOINT).forEach(intent::removeExtra)
    }

    private fun requestCode(target: Target, slot: Int): Int = ((target.tag.orEmpty() + "\u0000" + target.notificationId).hashCode() * 31) + slot
}
