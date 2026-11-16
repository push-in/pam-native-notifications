package dev.pam.notifications

import dev.pam.nativeapp.modules.*

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

public object PamPushNotifications {
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var waiter: ModuleCompletion? = null
    private var preferences: SharedPreferences? = null

    /** The tap that started this runtime, exported to PHP before it boots. */
    private var launchToken: String? = null
    private var launchPayload: ByteArray? = null
    private var launchExported = false

    @JvmStatic
    public fun attach(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            val stored = runCatching {
                JSONArray(preferences?.getString(PREFERENCES_EVENTS, "[]") ?: "[]")
            }.getOrDefault(JSONArray())
            for (index in 0 until stored.length()) {
                val encoded = stored.optString(index)
                val payload = runCatching {
                    Base64.decode(encoded, Base64.NO_WRAP)
                }.getOrNull() ?: continue
                if (payload.isNotEmpty()) events.addLast(payload)
            }
            while (events.size > MAX_QUEUED_EVENTS) events.removeFirst()
            persistLocked()
        }
    }

    @JvmStatic
    public fun reportReceived(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        report(1, id, title, body, dataJson, deepLink)
    }

    /** Reports a received push that a PushRendering rule already displayed natively. */
    @JvmStatic
    public fun reportRendered(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        report(1, id, title, body, dataJson, deepLink, rendered = true)
    }

    @JvmStatic
    public fun reportOpened(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        report(2, id, title, body, dataJson, deepLink)
    }

    /**
     * The notification tap that cold-starts the runtime. Called from
     * `HostEvents.initialIntent`, on the main thread before PHP boots: the
     * event is exported as [LAUNCH_ENV] for
     * `PushNotifications::takeLaunchNotification()` and queued as before for
     * `listen()`. Whichever path takes it first consumes the other.
     */
    @JvmStatic
    public fun captureLaunch(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        val token = java.util.UUID.randomUUID().toString()
        val payload = encode(2, id, title, body, dataJson, deepLink, rendered = false, launch = token)
        synchronized(lock) {
            launchToken = token
            launchPayload = payload
            exportLaunchLocked(Base64.encodeToString(payload, Base64.NO_WRAP))
        }
        deliver(payload)
    }

    /**
     * A runtime that starts without a notification tap exports nothing
     * (a previous runtime of this process may have left one unconsumed).
     */
    @JvmStatic
    public fun clearLaunch() {
        synchronized(lock) {
            launchToken = null
            launchPayload = null
            exportLaunchLocked("")
        }
    }

    /** `consumeLaunchNotification`: PHP took the launch tap synchronously. */
    internal fun consumeLaunch(token: String) {
        synchronized(lock) {
            if (token.isEmpty() || token != launchToken) return
            val payload = launchPayload
            if (payload != null && events.removeIf { it === payload }) persistLocked()
            launchToken = null
            launchPayload = null
            exportLaunchLocked("")
        }
    }

    internal fun next(completion: ModuleCompletion) {
        val event = synchronized(lock) {
            require(waiter == null) { "Only one push listener can wait at a time" }
            if (events.isEmpty()) {
                waiter = completion
                null
            } else {
                events.removeFirst().also {
                    persistLocked()
                    launchDeliveredLocked(it)
                }
            }
        }
        if (event != null) completion.complete(ModuleResultStatus.SUCCESS, event)
    }

    internal fun close(message: String) {
        val pending = synchronized(lock) {
            val value = waiter
            waiter = null
            value
        }
        pending?.complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    internal fun prepareReload() {
        synchronized(lock) {
            waiter = null
        }
    }

    private fun report(
        event: Int,
        id: String,
        title: String,
        body: String,
        dataJson: String,
        deepLink: String,
        rendered: Boolean = false,
    ) {
        deliver(encode(event, id, title, body, dataJson, deepLink, rendered, launch = ""))
    }

    private fun encode(
        event: Int,
        id: String,
        title: String,
        body: String,
        dataJson: String,
        deepLink: String,
        rendered: Boolean,
        launch: String,
    ): ByteArray {
        val values = mutableMapOf<String, WireValue>(
            "event" to WireValue.Integer(event.toLong()),
            "id" to WireValue.Text(id.take(512)),
            "title" to WireValue.Text(title.take(4_096)),
            "body" to WireValue.Text(body.take(16_384)),
            "data" to WireValue.Text(
                runCatching { JSONObject(dataJson.take(MAX_DATA_BYTES)).toString() }
                    .getOrDefault("{}"),
            ),
            "deepLink" to WireValue.Text(deepLink.take(8_192)),
            "rendered" to WireValue.Flag(rendered),
        )
        if (launch.isNotEmpty()) values["launch"] = WireValue.Text(launch)
        return WireMap.encode(values)
    }

    private fun deliver(payload: ByteArray) {
        val pending = synchronized(lock) {
            val value = waiter
            if (value == null) {
                if (events.size >= MAX_QUEUED_EVENTS) events.removeFirst()
                events.addLast(payload)
                persistLocked()
            } else {
                waiter = null
                launchDeliveredLocked(payload)
            }
            value
        }
        pending?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    /** The listener received the launch tap: PHP must not take it again. */
    private fun launchDeliveredLocked(payload: ByteArray) {
        if (launchPayload == null || payload !== launchPayload) return
        launchToken = null
        launchPayload = null
        exportLaunchLocked("")
    }

    /**
     * Process environment, read by PHP's getenv(). Set before PHP boots and
     * emptied once consumed, so a later PHP request (hot reload, crash
     * recovery) does not see it. bionic replaces only this entry's pointer
     * (the old string is never freed), which a concurrent getenv tolerates.
     */
    private fun exportLaunchLocked(value: String) {
        // Normal launches never touch the environment.
        if (value.isEmpty() && !launchExported) return
        launchExported = value.isNotEmpty()
        runCatching { android.system.Os.setenv(LAUNCH_ENV, value, true) }
    }

    private fun persistLocked() {
        val storage = preferences ?: return
        val encoded = JSONArray()
        events.forEach { payload ->
            encoded.put(Base64.encodeToString(payload, Base64.NO_WRAP))
        }
        storage.edit().putString(PREFERENCES_EVENTS, encoded.toString()).apply()
    }

    /** `PushNotifications::LAUNCH_ENV`. */
    public const val LAUNCH_ENV: String = "PAM_NATIVE_NOTIFICATIONS_LAUNCH"
    private const val MAX_QUEUED_EVENTS = 64
    private const val MAX_DATA_BYTES = 256 * 1024
    private const val PREFERENCES_EVENTS = "events"
    private const val PREFERENCES_NAME = "pam-native-push"
}
