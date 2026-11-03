package dev.pam.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

/** Preserve the host's existing notification glyph without linking its generated R class. */
internal fun notificationSmallIcon(context: Context): Int =
    context.resources.getIdentifier("pam_icon", "drawable", context.packageName)
        .takeIf { it != 0 } ?: context.applicationInfo.icon

/**
 * Status-bar glyph of every plugin notification. A host drawable named
 * `pam_notification_icon` wins; otherwise the app's own adaptive-icon
 * monochrome layer (`android.icon.monochrome`, an alpha-only glyph on the
 * 108 dp canvas) is cropped to its central 88 dp, which leaves the glyph with
 * the padding of a regular status-bar icon. Hosts without either keep `pam_icon`.
 */
internal fun NotificationCompat.Builder.setPamSmallIcon(context: Context): NotificationCompat.Builder {
    val resources = context.resources
    val dedicated = resources.getIdentifier("pam_notification_icon", "drawable", context.packageName)
    if (dedicated != 0) return setSmallIcon(dedicated)
    val glyph = MonochromeGlyph.get(context)
    return if (glyph != null) setSmallIcon(IconCompat.createWithBitmap(glyph)) else setSmallIcon(notificationSmallIcon(context))
}

/** Same glyph for platform builders (scheduled local notifications). */
internal fun android.app.Notification.Builder.setPamSmallIcon(context: Context): android.app.Notification.Builder {
    val dedicated = context.resources.getIdentifier("pam_notification_icon", "drawable", context.packageName)
    if (dedicated != 0) return setSmallIcon(dedicated)
    val glyph = MonochromeGlyph.get(context)
    return if (glyph != null) setSmallIcon(android.graphics.drawable.Icon.createWithBitmap(glyph)) else setSmallIcon(notificationSmallIcon(context))
}

private object MonochromeGlyph {
    private const val EDGE = 96
    private const val CANVAS_DP = 108f
    private const val CROP_DP = 88f
    @Volatile private var loaded = false
    @Volatile private var glyph: Bitmap? = null

    fun get(context: Context): Bitmap? {
        if (loaded) return glyph
        synchronized(this) {
            if (!loaded) {
                glyph = runCatching { load(context) }.getOrNull()
                loaded = true
            }
        }
        return glyph
    }

    private fun load(context: Context): Bitmap? {
        val id = context.resources.getIdentifier("pam_launcher_monochrome", "drawable", context.packageName)
        if (id == 0) return null
        val source = BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false })
            ?: return null
        val side = (minOf(source.width, source.height) * CROP_DP / CANVAS_DP).toInt()
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val output = Bitmap.createBitmap(EDGE, EDGE, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            source,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, EDGE, EDGE),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        source.recycle()
        return output
    }
}
