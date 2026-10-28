package dev.pam.notifications

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.pam.nativeapp.HostEvents
import dev.pam.nativeapp.HostEventListener

class PluginHostProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = requireNotNull(context).applicationContext
        PamPushNotifications.attach(app)
        PamNotificationActions.attach(app)
        HostEvents.register(object : HostEventListener {
            override fun initialIntent(context: android.content.Context, intent: android.content.Intent?) = openedIntent(context, intent)
            override fun openedIntent(context: android.content.Context, intent: android.content.Intent?) {
                if (intent?.getBooleanExtra("pam.notification.opened", false) != true) return
                PamPushNotifications.reportOpened(
                    intent.getStringExtra("pam.notification.id").orEmpty(),
                    intent.getStringExtra("pam.notification.title").orEmpty(),
                    intent.getStringExtra("pam.notification.body").orEmpty(),
                    intent.getStringExtra("pam.notification.data").orEmpty().ifEmpty { "{}" },
                    intent.getStringExtra("pam.notification.deepLink").orEmpty(),
                )
                intent.removeExtra("pam.notification.opened")
            }
            override fun foreground(active: Boolean) = PamActiveRoute.setForeground(active)
            override fun prepareReload() {
                PamPushNotifications.prepareReload()
                PamNotificationActions.prepareReload()
            }
        })
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
