package com.innovation313.glowedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
            val autostart = prefs.getBoolean("autostart", false)
            if (autostart && Settings.canDrawOverlays(context)) {
                val svc = Intent(context, VisualizerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }
        }
    }
}
