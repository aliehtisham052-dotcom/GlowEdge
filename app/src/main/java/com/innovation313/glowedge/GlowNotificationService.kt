package com.innovation313.glowedge

import android.content.Intent
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Detects incoming notifications and asks the glow service to flash in that app's color. */
class GlowNotificationService : NotificationListenerService() {
    companion object {
        private val APP_COLORS = mapOf(
            "com.whatsapp" to "#25D366", "com.whatsapp.w4b" to "#25D366",
            "org.telegram.messenger" to "#0088CC", "com.facebook.orca" to "#0084FF",
            "com.instagram.android" to "#E1306C", "com.facebook.katana" to "#1877F2",
            "com.google.android.gm" to "#EA4335", "com.snapchat.android" to "#FFFC00",
            "com.twitter.android" to "#1DA1F2", "com.linkedin.android" to "#0A66C2",
            "com.spotify.music" to "#1DB954"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Settings.notifGlow(this)) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || sbn.isOngoing) return
        val hex = APP_COLORS[pkg]
        val color = if (hex != null) Color.parseColor(hex)
        else sbn.notification.color.let { if (it != 0) it else Color.parseColor("#FFD54F") }
        sendBroadcast(Intent(VisualizerService.ACTION_NOTIF).apply {
            setPackage(packageName)
            putExtra(VisualizerService.EXTRA_COLOR, color)
        })
    }
}
