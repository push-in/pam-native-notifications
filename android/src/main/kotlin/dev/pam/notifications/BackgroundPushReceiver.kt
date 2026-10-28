package dev.pam.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pam.nativeapp.push.BackgroundPush

/** Background rendering works without an Activity or PHP runtime. */
class BackgroundPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        try {
            worker.execute {
                try {
                    val id = intent.getStringExtra(BackgroundPush.EXTRA_ID).orEmpty()
                    val title = intent.getStringExtra(BackgroundPush.EXTRA_TITLE).orEmpty()
                    val body = intent.getStringExtra(BackgroundPush.EXTRA_BODY).orEmpty()
                    val data = intent.getStringExtra(BackgroundPush.EXTRA_DATA_JSON) ?: "{}"
                    val link = intent.getStringExtra(BackgroundPush.EXTRA_DEEP_LINK).orEmpty()
                    PamPushNotifications.attach(context)
                    if (runCatching { PamPushRendering.render(context, id, title, body, data) }.getOrDefault(false)) {
                        PamPushNotifications.reportRendered(id, title, body, data, link)
                    } else PamPushNotifications.reportReceived(id, title, body, data, link)
                } finally { pending.finish() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.finish()
        }
    }

    companion object {
        // Bound both parallel work and queued pushes; no permanent idle worker.
        private val worker = java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            java.util.concurrent.ThreadFactory { runnable ->
                Thread(runnable, "pam-push-render").apply { isDaemon = true }
            },
        )
    }
}
