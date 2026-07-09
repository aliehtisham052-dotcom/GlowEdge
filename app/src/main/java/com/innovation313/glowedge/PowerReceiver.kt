package com.innovation313.glowedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Handles charging glow even when the main visualizer service is not running.
 * On plug/unplug it starts the service (if overlay permission is granted and
 * charging glow is enabled) and asks it to show a charging flash.
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_POWER_CONNECTED &&
            action != Intent.ACTION_POWER_DISCONNECTED) return

        val prefs = context.getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("charging_glow", true)) return
        if (!Settings.canDrawOverlays(context)) return

        val color = if (action == Intent.ACTION_POWER_CONNECTED) "#00E676" else "#FF9100"
        val svc = Intent(context, VisualizerService::class.java).apply {
            this.action = VisualizerService.ACTION_CHARGING_FLASH
            putExtra(VisualizerService.EXTRA_FLASH_COLOR, android.graphics.Color.parseColor(color))
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
        } catch (_: Exception) {
        }
    }
}
