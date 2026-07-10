package com.innovation313.glowedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings

/** Starts the glow automatically after boot when Auto-Start is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.autostart(context)) return
        if (!AndroidSettings.canDrawOverlays(context)) return
        val svc = Intent(context, VisualizerService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
    }
}
