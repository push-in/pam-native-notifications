package dev.pam.notifications

import dev.pam.nativeapp.modules.*

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.Activity
import androidx.core.app.NotificationManagerCompat
import dev.pam.nativeapp.PermissionHost
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.json.JSONObject

class NotificationsModule(context: Context) : NativeModule, SurfaceRetirableModule {
    private val activity = context as Activity
    private val main = Handler(Looper.getMainLooper())

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-notifications").apply { isDaemon = true }
    }

    init {
        PamPushNotifications.attach(activity.applicationContext)
        PamNotificationActions.attach(activity.applicationContext)
    }

    private fun background(completion: ModuleCompletion, work: () -> ByteArray) {
        try {
            worker.execute {
                runCatching(work).fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = {
                        completion.complete(ModuleResultStatus.FAILURE, (it.message ?: "Notification failed").toByteArray())
                    },
                )
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            completion.complete(ModuleResultStatus.FAILURE, "Notifications module is closed".toByteArray())
        }
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "requestPermission" -> requestPermission(completion)
                "schedule" -> schedule(payload, completion)
                "cancel" -> cancel(payload, completion)
                "registerPush" -> registerPush(completion)
                "unregisterPush" -> unregisterPush(completion)
                "nextPushEvent" -> PamPushNotifications.next(completion)
                "configurePushEvents" -> {
                    val persist = (WireMap.decode(payload)["persistRendered"] as? WireValue.Flag)?.value ?: false
                    PamPushNotifications.persistRendered(activity.applicationContext, persist)
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "consumeLaunchNotification" -> {
                    PamPushNotifications.consumeLaunch((WireMap.decode(payload)["launch"] as? WireValue.Text)?.value.orEmpty())
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "showConversation" -> background(completion) {
                    val spec = ConversationSpec.from(JSONObject(WireMap.decode(payload).text("spec")))
                    PamConversationNotifications.show(activity.applicationContext, spec)
                    WireMap.encode(mapOf("key" to WireValue.Text(spec.key)))
                }
                "cancelConversation" -> {
                    PamConversationNotifications.cancel(activity.applicationContext, WireMap.decode(payload).text("key"))
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "nextAction" -> PamNotificationActions.next(completion)
                "registerPushRendering" -> {
                    PamPushRendering.register(activity.applicationContext, WireMap.decode(payload).text("rule"))
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "forgetPushRendering" -> {
                    val values = WireMap.decode(payload)
                    if ((values["all"] as? WireValue.Flag)?.value == true) {
                        PamPushRendering.clear(activity.applicationContext)
                    } else {
                        PamPushRendering.forget(activity.applicationContext, values.text("field"), values.text("type"))
                    }
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "setCredential" -> background(completion) {
                    val values = WireMap.decode(payload)
                    PamNotificationCredentials.set(activity.applicationContext, values.text("account"), values.text("token"))
                    ByteArray(0)
                }
                "removeCredential" -> background(completion) {
                    PamNotificationCredentials.remove(activity.applicationContext, WireMap.decode(payload).text("account"))
                    ByteArray(0)
                }
                "replaceCredentials" -> background(completion) {
                    val tokens = JSONObject(WireMap.decode(payload).text("tokens"))
                    PamNotificationCredentials.replace(
                        activity.applicationContext,
                        tokens.keys().asSequence().associateWith { tokens.getString(it) },
                    )
                    ByteArray(0)
                }
                "setActiveRoute" -> {
                    val values = WireMap.decode(payload)
                    PamActiveRoute.update(
                        values.text("name"),
                        (values["params"] as? WireValue.Text)?.value ?: "{}",
                        (values["path"] as? WireValue.Text)?.value.orEmpty(),
                    )
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown notifications method $method")
            }
        }.onFailure { error ->
            completion.complete(ModuleResultStatus.FAILURE, (error.message ?: "Notification failed").toByteArray())
        }
    }

    override fun retireSurface() {
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    override fun close() {
        retireSurface()
        PamPushNotifications.close("Notifications module closed")
        PamNotificationActions.close("Notifications module closed")
    }

    private fun registerPush(completion: ModuleCompletion) {
        FirebaseTokens.await(main, "getToken", "Push token registration", completion) { task ->
            val token = task.result as? String
            require(!token.isNullOrBlank()) { "Firebase returned an empty push token" }
            WireMap.encode(
                mapOf(
                    "token" to WireValue.Text(token),
                    "provider" to WireValue.Integer(1),
                ),
            )
        }
    }

    private fun unregisterPush(completion: ModuleCompletion) {
        FirebaseTokens.await(main, "deleteToken", "Push token unregistration", completion) { ByteArray(0) }
    }

    private fun requestPermission(completion: ModuleCompletion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime prompt below Android 13: the app's notification switch is the answer
            // (off means blocked; only Settings can turn it back on).
            completion.permission(NotificationManagerCompat.from(activity).areNotificationsEnabled())
            return
        }
        (activity as PermissionHost).requestPamPermission(Manifest.permission.POST_NOTIFICATIONS) {
            completion.permission(it)
        }
    }

    private fun schedule(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val id = values.text("id")
        val intent = notificationIntent(activity, id, values)
        val delay = values.integer("delaySeconds", 0).coerceIn(0, 31_536_000)
        if (delay == 0L) {
            NotificationReceiver().onReceive(activity, intent)
        } else {
            val alarm = activity.getSystemService(AlarmManager::class.java)
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delay * 1_000,
                pending(activity, id, intent),
            )
        }
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun cancel(payload: ByteArray, completion: ModuleCompletion) {
        val id = WireMap.decode(payload).text("id")
        val intent = Intent(activity, NotificationReceiver::class.java)
        activity.getSystemService(AlarmManager::class.java).cancel(pending(activity, id, intent))
        activity.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun ModuleCompletion.permission(granted: Boolean) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("granted" to WireValue.Flag(granted))),
        )
    }

    private fun Map<String, WireValue>.text(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: error("Missing text field $key")

    private fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
        (this[key] as? WireValue.Integer)?.value ?: fallback

    companion object {
        private fun pending(context: Context, id: String, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun notificationIntent(
            context: Context,
            id: String,
            values: Map<String, WireValue>,
        ): Intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", (values["title"] as? WireValue.Text)?.value.orEmpty())
            putExtra("body", (values["body"] as? WireValue.Text)?.value.orEmpty())
            putExtra("importance", ((values["importance"] as? WireValue.Integer)?.value ?: 2L).toInt())
            putExtra("data", (values["data"] as? WireValue.Text)?.value.orEmpty())
            putExtra("deepLink", (values["deepLink"] as? WireValue.Text)?.value.orEmpty())
        }
    }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val importance = intent.getIntExtra("importance", 2).coerceIn(1, 4)
        val channelId = "pam-native-$importance"
        val nativeImportance = when (importance) {
            1 -> NotificationManager.IMPORTANCE_LOW
            3 -> NotificationManager.IMPORTANCE_HIGH
            4 -> NotificationManager.IMPORTANCE_MAX
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Pam notifications", nativeImportance),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("pam.notification.opened", true)
            putExtra("pam.notification.id", intent.getStringExtra("id").orEmpty())
            putExtra("pam.notification.title", intent.getStringExtra("title").orEmpty())
            putExtra("pam.notification.body", intent.getStringExtra("body").orEmpty())
            putExtra("pam.notification.data", intent.getStringExtra("data").orEmpty())
            putExtra("pam.notification.deepLink", intent.getStringExtra("deepLink").orEmpty())
        }
        val content = launch?.let {
            PendingIntent.getActivity(
                context,
                intent.getStringExtra("id").orEmpty().hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            // Android 7.x: no channels; priority and defaults stand in for importance.
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
                .setPriority(
                    when (importance) {
                        1 -> android.app.Notification.PRIORITY_LOW
                        3 -> android.app.Notification.PRIORITY_HIGH
                        4 -> android.app.Notification.PRIORITY_MAX
                        else -> android.app.Notification.PRIORITY_DEFAULT
                    },
                )
                .setDefaults(if (importance >= 2) android.app.Notification.DEFAULT_ALL else 0)
        }
        val notification = builder
            .setPamSmallIcon(context)
            .setContentTitle(intent.getStringExtra("title").orEmpty())
            .setContentText(intent.getStringExtra("body").orEmpty())
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        manager.notify(intent.getStringExtra("id").orEmpty().hashCode(), notification)
    }
}
