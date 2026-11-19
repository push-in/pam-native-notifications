package dev.pam.notifications

import android.annotation.SuppressLint
import androidx.core.content.edit
import java.util.TimeZone
import java.util.Locale
import java.text.SimpleDateFormat
import android.os.Build
import dev.pam.nativeapp.modules.*

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Route currently focused by the PHP navigator, reported by the runtime.
 * Push rendering uses it to suppress notifications for the open screen.
 */
public object PamActiveRoute {
    @Volatile internal var name: String = ""
    @Volatile internal var params: JSONObject = JSONObject()
    @Volatile internal var path: String = ""
    @Volatile internal var foreground: Boolean = false

    internal fun update(name: String, paramsJson: String, path: String) {
        this.name = name.take(256)
        this.params = runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())
        this.path = path.take(8_192)
    }

    @JvmStatic
    public fun setForeground(foreground: Boolean) {
        this.foreground = foreground
    }

    /**
     * Suppression entries are `{route, params}`, `{path}` templates or
     * `{foreground: true}`, optionally narrowed by `where` (field = value) and
     * `blank` (every listed field missing or empty, e.g. no recipient).
     */
    internal fun matches(rules: JSONArray?, variables: Map<String, String>): Boolean {
        if (!foreground || rules == null) return false
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val routePath = rule.optString("path")
            if (routePath.isNotEmpty()) {
                if (!NotificationTemplate.complete(routePath, variables)) continue
                val expected = normalize(NotificationTemplate.render(routePath, variables))
                if (path.isNotEmpty() && normalize(path) == expected) return true
                continue
            }
            if (rule.optBoolean("foreground")) {
                val conditions = rule.optJSONObject("where") ?: JSONObject()
                val blank = rule.optJSONArray("blank") ?: JSONArray()
                val whereMatched = conditions.keys().asSequence().all { field -> variables[field] == conditions.optString(field) }
                val blankMatched = (0 until blank.length()).all { variables[blank.optString(it)].isNullOrBlank() }
                if (whereMatched && blankMatched) return true
                continue
            }
            if (rule.optString("route") != name || name.isEmpty()) continue
            val expectedParams = rule.optJSONObject("params") ?: JSONObject()
            val matched = expectedParams.keys().asSequence().all { key ->
                val template = expectedParams.optString(key)
                NotificationTemplate.complete(template, variables) &&
                    params.opt(key)?.toString() == NotificationTemplate.render(template, variables)
            }
            if (matched) return true
        }
        return false
    }

    private fun normalize(value: String): String =
        "/" + value.substringBefore('?').substringBefore('#').trim('/')
}

/**
 * Declarative push rendering. PHP registers rules once; the Firebase service
 * renders matching data-only pushes natively, even when PHP is suspended or
 * the process was started just for the push.
 */
public object PamPushRendering {
    private const val PREFERENCES = "pam-native-push-rendering"
    internal const val KIND_CONVERSATION = 1
    internal const val KIND_NOTIFICATION = 2
    internal const val KIND_DISMISS = 3

    internal fun register(context: Context, ruleJson: String) {
        val rule = JSONObject(ruleJson)
        val type = rule.getString("type")
        require(type.isNotBlank() && type.length <= 128) { "Push rendering type is invalid" }
        require(rule.optInt("kind") in KIND_CONVERSATION..KIND_DISMISS) { "Push rendering kind is invalid" }
        preferences(context).edit {
            putString(key(rule.optString("field", "type"), type, rule.optJSONObject("where"), rule.optJSONArray("requires")), rule.toString())
        }
    }

    internal fun forget(context: Context, field: String, type: String) {
        val prefix = key(field, type, null, null)
        val keys = preferences(context).all.keys.filter { it == prefix || it.startsWith(prefix + "\u0000") }
        preferences(context).edit { keys.forEach { remove(it) } }
    }

    /** The registered rule for this push: type field plus every `where` condition; the most specific wins. */
    internal fun select(context: Context, data: JSONObject): JSONObject? =
        preferences(context).all.values.asSequence()
            .mapNotNull { value -> (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } }
            .filter { candidate ->
                data.opt(candidate.optString("field", "type"))?.toString() == candidate.optString("type") &&
                    (candidate.optJSONObject("where") ?: JSONObject()).let { where ->
                        where.keys().asSequence().all { field -> data.opt(field)?.toString() == where.optString(field) }
                    } &&
                    (candidate.optJSONArray("requires") ?: JSONArray()).let { required ->
                        (0 until required.length()).all { index ->
                            !data.opt(required.optString(index))?.toString().isNullOrBlank() && data.opt(required.optString(index)) != JSONObject.NULL
                        }
                    }
            }
            .maxByOrNull { (it.optJSONObject("where")?.length() ?: 0) + (it.optJSONArray("requires")?.length() ?: 0) }

    internal fun clear(context: Context) {
        preferences(context).edit { clear() }
    }

    /** Returns true when a rule rendered (or intentionally dismissed) the push. */
    @JvmStatic
    public fun render(context: Context, id: String, title: String, body: String, dataJson: String): Boolean {
        val data = runCatching { JSONObject(dataJson) }.getOrNull() ?: return false
        val rule = select(context, data) ?: return false
        val variables = NotificationTemplate.variables(dataJson) + mapOf(
            "_id" to id,
            "_title" to title,
            "_body" to body,
        )
        return runCatching { apply(context, rule, variables, dataJson) }.getOrDefault(false)
    }

    private fun apply(context: Context, rule: JSONObject, variables: Map<String, String>, dataJson: String): Boolean {
        fun text(template: String): String =
            if (template.isEmpty()) "" else NotificationTemplate.render(template, variables).trim()

        when (rule.getInt("kind")) {
            KIND_DISMISS -> {
                val key = text(rule.optJSONObject("conversation")?.optString("key").orEmpty())
                if (key.isEmpty()) return false
                PamConversationNotifications.cancel(context, key)
                return true
            }
            KIND_CONVERSATION -> {
                if (PamActiveRoute.matches(rule.optJSONArray("suppress"), variables)) return true
                val conversation = rule.getJSONObject("conversation")
                val message = rule.getJSONObject("message")
                val key = text(conversation.optString("key"))
                val messageText = text(message.optString("text"))
                val sender = text(message.optString("sender"))
                if (key.isEmpty() || messageText.isEmpty() || sender.isEmpty()) return false
                val reply = rule.optJSONObject("reply")
                // hideWhen: the push says this recipient may not reply (e.g. can_reply = "0").
                val replyHidden = reply?.optJSONObject("hideWhen")?.let { hide ->
                    hide.keys().asSequence().any { field -> variables[field] == hide.optString(field) }
                } ?: false
                val markRead = rule.optJSONObject("markRead")
                PamConversationNotifications.show(
                    context,
                    ConversationSpec(
                        key = key,
                        title = text(conversation.optString("title")),
                        group = text(conversation.optString("group")).lowercase() in setOf("1", "true", "yes"),
                        self = ConversationPerson(rule.optString("self").ifBlank { "You" }),
                        messages = listOf(
                            ConversationMessage(
                                id = text(message.optString("id")),
                                text = messageText.take(ConversationMessage.MAX_TEXT),
                                timestamp = timestamp(text(message.optString("timestamp"))),
                                sender = ConversationPerson(
                                    name = sender,
                                    avatar = text(message.optString("avatar")),
                                    key = text(message.optString("senderKey")),
                                ),
                            ),
                        ),
                        replyLabel = if (replyHidden) "" else reply?.optString("label").orEmpty(),
                        replyEndpoint = reply?.optJSONObject("endpoint"),
                        markReadLabel = markRead?.optString("label").orEmpty(),
                        markReadEndpoint = markRead?.optJSONObject("endpoint"),
                        deepLink = text(rule.optString("deepLink")),
                        dataJson = dataJson,
                        importance = rule.optInt("importance", 3),
                        channelId = rule.optString("channelId").ifBlank { ConversationSpec.DEFAULT_CHANNEL },
                        channelName = rule.optString("channelName").ifBlank { "Messages" },
                        replyFailures = rule.optJSONObject("replyFailures"),
                        failure = null,
                        style = PamNotificationStyle.resolve(rule.optJSONObject("style"), ::text),
                        buttons = PamNotificationButtons.resolve(rule.optJSONArray("actions"), ::text),
                        alertOnce = if (rule.isNull("alertOnce")) true else rule.optBoolean("alertOnce", true),
                    ),
                )
                return true
            }
            KIND_NOTIFICATION -> {
                if (PamActiveRoute.matches(rule.optJSONArray("suppress"), variables)) return true
                val title = text(rule.optString("title"))
                if (title.isEmpty()) return false
                notify(
                    context, rule, variables["_id"].orEmpty(), title, text(rule.optString("body")), text(rule.optString("deepLink")), dataJson,
                    PamNotificationStyle.resolve(rule.optJSONObject("style"), ::text),
                    Presentation(
                        key = text(rule.optString("key")).take(256),
                        subText = text(rule.optString("subText")).take(256),
                        buttons = PamNotificationButtons.resolve(rule.optJSONArray("actions"), ::text),
                    ),
                )
                return true
            }
        }
        return false
    }

    /**
     * Push-time values of a notification() rule: the replacement key
     * (PushRenderingRule::key(), e.g. '{user_id}:{thread_id}'), the subtitle
     * and the buttons with their templates rendered.
     */
    internal data class Presentation(
        val key: String = "",
        val subText: String = "",
        val buttons: JSONArray? = null,
    ) {
        /** Same key = same notification: a new push replaces the previous one of the subject. */
        val tag: String get() = if (key.isEmpty()) TAG else "$TAG:$key"

        fun notificationId(pushId: String): Int = if (key.isEmpty()) pushId.hashCode() else KEYED_ID
    }

    // canPostNotifications checks POST_NOTIFICATIONS (API 33+) and SecurityException is caught.
    @SuppressLint("MissingPermission")
    private fun notify(
        context: Context,
        rule: JSONObject,
        id: String,
        title: String,
        body: String,
        deepLink: String,
        dataJson: String,
        style: JSONObject?,
        presentation: Presentation,
    ) {
        val notification = buildNotification(context, rule, id, title, body, deepLink, dataJson, style, presentation)
        val compat = NotificationManagerCompat.from(context)
        if (!PamConversationNotifications.canPostNotifications(context, compat)) return
        try {
            compat.notify(presentation.tag, presentation.notificationId(id), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Standard notification of a rule, with its buttons (PushRenderingRule::action() / openAction()). */
    internal fun buildNotification(
        context: Context,
        rule: JSONObject,
        id: String,
        title: String,
        body: String,
        deepLink: String,
        dataJson: String,
        style: JSONObject? = null,
        presentation: Presentation = Presentation(buttons = rule.optJSONArray("actions")),
    ): android.app.Notification {
        val channelId = rule.optString("channelId").ifBlank { "pam-push" }
        PamNotificationStyle.ensureChannel(
            context,
            channelId,
            rule.optString("channelName").ifBlank { "Notifications" },
            if (rule.optInt("importance", 2) >= 3) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
            style,
        )
        val tag = presentation.tag
        val notificationId = presentation.notificationId(id)
        val picture = style?.optString("image")?.takeIf { it.isNotEmpty() }?.let { PamConversationNotifications.picture(context, it) }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("pam.notification.opened", true)
            putExtra("pam.notification.id", id)
            putExtra("pam.notification.title", title)
            putExtra("pam.notification.body", body)
            putExtra("pam.notification.data", dataJson)
            putExtra("pam.notification.deepLink", deepLink)
        }
        // A keyed rule replaces the previous notification of the subject; it
        // alerts again only once that one was dismissed (unless alertOnce(false)).
        val alertOnce = if (rule.isNull("alertOnce")) presentation.key.isNotEmpty() else rule.optBoolean("alertOnce")
        val builder = NotificationCompat.Builder(context, channelId)
            .setPamSmallIcon(context)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                if (picture != null) {
                    NotificationCompat.BigPictureStyle().bigPicture(picture).setBigContentTitle(title).setSummaryText(body)
                } else {
                    NotificationCompat.BigTextStyle().bigText(body)
                },
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnce)
            // Android 7.x has no channels: priority and defaults carry the importance.
            .setPriority(if (rule.optInt("importance", 2) >= 3) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply {
                if (presentation.subText.isNotEmpty()) setSubText(presentation.subText)
                launch?.let {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            if (presentation.key.isEmpty()) id.hashCode() else tag.hashCode(),
                            it,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        PamNotificationStyle.decorate(context, builder, style)
        PamNotificationButtons.add(
            context,
            builder,
            presentation.buttons,
            PamNotificationButtons.Target(id, title, body, dataJson, deepLink, tag, notificationId),
        )
        return builder.build()
    }

    internal fun timestamp(value: String): Long {
        if (value.isEmpty()) return System.currentTimeMillis()
        value.toLongOrNull()?.let { return if (it in 1..99_999_999_999L) it * 1_000 else it }
        value.toDoubleOrNull()?.let { return (if (it < 100_000_000_000.0) it * 1_000 else it).toLong() }
        val parsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        } else {
            parseIsoInstant(value)
        }
        return parsed ?: System.currentTimeMillis()
    }

    private val ISO_INSTANT = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})$""")

    /** `Instant.parse` (java.time is API 26+) for Android 7.x: UTC or offset, up to nanoseconds. */
    internal fun parseIsoInstant(value: String): Long? {
        val match = ISO_INSTANT.matchEntire(value) ?: return null
        val (seconds, fraction, zone) = match.destructured
        val local = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(seconds)?.time
        }.getOrNull() ?: return null
        val offsetMinutes = if (zone == "Z") {
            0
        } else {
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.substring(0, 2).toInt() * 60 + digits.substring(2).toInt()
            if (zone[0] == '-') -minutes else minutes
        }
        val base = local - offsetMinutes * 60_000L
        return base + fraction.padEnd(3, '0').take(3).toLong()
    }

    private const val TAG = "pam-push"
    private const val KEYED_ID = 0x50414e

    private fun key(field: String, type: String, where: JSONObject?, requires: JSONArray?): String {
        val base = "$field\u0000$type"
        val conditions = (where?.keys()?.asSequence()?.sorted()?.map { "$it=${where.optString(it)}" }?.toList() ?: emptyList()) +
            (0 until (requires?.length() ?: 0)).map { "$" + requires!!.optString(it) }.sorted()
        return if (conditions.isEmpty()) base else base + conditions.joinToString("") { "\u0000$it" }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
