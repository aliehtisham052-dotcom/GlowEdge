package com.innovation313.glowedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val prefs = context.getSharedPreferences("glowedge_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_glow", false)) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Blue "call" glow for incoming / ongoing calls
                val glow = Intent(GlowNotificationService.ACTION_NOTIFICATION_GLOW).apply {
                    setPackage(context.packageName)
                    putExtra(GlowNotificationService.EXTRA_COLOR,
                        android.graphics.Color.parseColor("#00B0FF"))
                    putExtra("repeat", true)
                }
                context.sendBroadcast(glow)
            }
        }
    }
}
