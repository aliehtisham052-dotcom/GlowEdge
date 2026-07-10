package com.innovation313.glowedge

import android.content.Intent
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class GlowNotificationService : NotificationListenerService() {

    companion object {
        const val ACTION_NOTIFICATION_GLOW = "com.innovation313.glowedge.NOTIF_GLOW"
        const val EXTRA_COLOR = "color"

        // Popular app package -> brand color for the glow flash
        private val APP_COLORS = mapOf(
            "com.whatsapp" to "#25D366",
            "com.whatsapp.w4b" to "#25D366",
            "org.telegram.messenger" to "#0088CC",
            "com.facebook.orca" to "#0084FF",
            "com.instagram.android" to "#E1306C",
            "com.facebook.katana" to "#1877F2",
            "com.google.android.gm" to "#EA4335",
            "com.android.dialer" to "#00C853",
            "com.google.android.dialer" to "#00C853",
            "com.android.mms" to "#2979FF",
            "com.snapchat.android" to "#FFFC00",
            "com.twitter.android" to "#1DA1F2",
            "com.linkedin.android" to "#0A66C2",
            "com.spotify.music" to "#1DB954"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = getSharedPreferences("glowedge_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("notif_glow", false)) return

        val pkg = sbn.packageName ?: return
        // Ignore our own and ongoing/silent system notifications
        if (pkg == packageName) return
        if (sbn.isOngoing) return

        val colorHex = APP_COLORS[pkg]
        val color = if (colorHex != null) Color.parseColor(colorHex)
        else {
            // Use the notification's own accent color if available, else gold
            val c = sbn.notification.color
            if (c != 0) c else Color.parseColor("#FFD54F")
        }

        val intent = Intent(ACTION_NOTIFICATION_GLOW).apply {
            setPackage(packageName)
            putExtra(EXTRA_COLOR, color)
        }
        sendBroadcast(intent)
    }
}
