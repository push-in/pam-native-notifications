package dev.pam.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pam.nativeapp.push.BackgroundPush

/** Background rendering works without an Activity or PHP runtime. */
class BackgroundPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val id = intent.getStringExtra(BackgroundPush.EXTRA_ID).orEmpty()
                val title = intent.getStringExtra(BackgroundPush.EXTRA_TITLE).orEmpty()
                val body = intent.getStringExtra(BackgroundPush.EXTRA_BODY).orEmpty()
                val data = intent.getStringExtra(BackgroundPush.EXTRA_DATA_JSON) ?: "{}"
                val link = intent.getStringExtra(BackgroundPush.EXTRA_DEEP_LINK).orEmpty()
                PamPushNotifications.attach(context)
                if (PamPushRendering.render(context, id, title, body, data)) {
                    PamPushNotifications.reportRendered(id, title, body, data, link)
                } else PamPushNotifications.reportReceived(id, title, body, data, link)
            } finally { pending.finish() }
        }.apply { name = "pam-push-render"; isDaemon = true }.start()
    }
}
