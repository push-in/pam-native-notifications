package dev.pam.notifications

import android.annotation.SuppressLint
import android.content.Context

/** Preserve the host's existing notification glyph without linking its generated R class. */
@SuppressLint("DiscouragedApi") // the host's drawable is not in this module's R class
internal fun notificationSmallIcon(context: Context): Int =
    context.resources.getIdentifier("pam_icon", "drawable", context.packageName)
        .takeIf { it != 0 } ?: context.applicationInfo.icon
