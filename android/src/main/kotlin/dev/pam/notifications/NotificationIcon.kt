package dev.pam.notifications

import android.content.Context

/** Preserve the host's existing notification glyph without linking its generated R class. */
internal fun notificationSmallIcon(context: Context): Int =
    context.resources.getIdentifier("pam_icon", "drawable", context.packageName)
        .takeIf { it != 0 } ?: context.applicationInfo.icon
