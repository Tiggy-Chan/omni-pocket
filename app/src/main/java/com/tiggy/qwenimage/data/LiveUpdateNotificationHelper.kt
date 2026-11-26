package com.tiggy.qwenimage.data

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Reflection-safe helper that upgrades ongoing download and local inference progress notifications
 * into native Android 16+ (API 36) Live Updates (`Notification.ProgressStyle` + promoted ongoing
 * status-bar chip) while compiling cleanly on SDK 35 and degrading smoothly on Android 9–15.
 */
object LiveUpdateNotificationHelper {
    private const val ANDROID_16_API = 36
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun isSupportedDevice(): Boolean = Build.VERSION.SDK_INT >= ANDROID_16_API

    fun canPostPromoted(context: Context): Boolean {
        if (!isSupportedDevice()) return false
        return runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            val method = nm.javaClass.getMethod("canPostPromotedNotifications")
            (method.invoke(nm) as? Boolean) == true
        }.getOrDefault(false)
    }

    /**
     * Applies determinate/indeterminate progress to [builder], upgrading to Android 16
     * `Notification.ProgressStyle` + promoted Live Update chip when running on API 36+.
     */
    fun applyProgressAndLiveUpdate(
        builder: Notification.Builder,
        fraction: Float?,
        shortChipText: String? = null
    ) {
        val pct1000 = fraction?.let { (it * 1000f).toInt().coerceIn(0, 1000) }
        val pct100 = fraction?.let { (it * 100f).toInt().coerceIn(0, 100) }

        if (isSupportedDevice()) {
            val applied = runCatching {
                val styleCls = Class.forName("android.app.Notification\$ProgressStyle")
                val style = styleCls.getDeclaredConstructor().newInstance()
                if (pct100 != null) {
                    runCatching {
                        val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                        val seg = segCls.getConstructor(Int::class.javaPrimitiveType).newInstance(100)
                        val addSeg = styleCls.getMethod("addProgressSegment", segCls)
                        addSeg.invoke(style, seg)
                    }
                    styleCls.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, pct100)
                } else {
                    styleCls.getMethod("setProgressIndeterminate", Boolean::class.javaPrimitiveType)
                        .invoke(style, true)
                }
                builder.setStyle(style as Notification.Style)
                val chip = shortChipText?.trim()?.take(12)?.takeIf { it.isNotEmpty() }
                    ?: pct100?.let { "$it%" }
                if (!chip.isNullOrBlank()) {
                    runCatching {
                        builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java)
                            .invoke(builder, chip)
                    }
                }
                builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                true
            }.getOrDefault(false)
            if (applied) return
        }

        // Standard Android 9–15 progress bar fallback
        if (pct1000 != null) {
            builder.setProgress(1000, pct1000, false)
        } else {
            builder.setProgress(0, 0, true)
        }
    }
}
