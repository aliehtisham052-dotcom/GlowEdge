package com.innovation313.glowedge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class VisualizerService : Service() {

    companion object {
        const val CHANNEL_ID = "glowedge_channel"
        const val ACTION_STOP = "com.innovation313.glowedge.STOP"
        @Volatile
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var edgeView: EdgeVisualizerView? = null
    private var visualizer: Visualizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundWithNotification()
        addOverlay()
        applyCurrentSettings()
        startVisualizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyCurrentSettings()
        return START_STICKY
    }

    private fun applyCurrentSettings() {
        val theme = ProfileManager.theme(this)
        edgeView?.applySettings(
            ProfileManager.style(this),
            theme.colorStart,
            theme.colorEnd,
            ProfileManager.thickness(this).toFloat(),
            ProfileManager.speed(this) / 10f
        )
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GlowEdge", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VisualizerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun addOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        edgeView = EdgeVisualizerView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager?.addView(edgeView, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun startVisualizer() {
        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        v: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 130) return
                        val bandCount = 32
                        val bands = FloatArray(bandCount)
                        var sum = 0f
                        for (i in 0 until bandCount) {
                            val bin = 1 + i * 2
                            val re = fft[bin * 2].toFloat()
                            val im = fft[bin * 2 + 1].toFloat()
                            var mag = sqrt(re * re + im * im) / 128f
                            mag = sqrt(mag.coerceIn(0f, 1f))
                            bands[i] = mag
                            sum += mag
                        }
                        val level = (sum / bandCount * 1.8f).coerceIn(0f, 1f)
                        edgeView?.setAudioData(level, bands)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) {
            // Restricted device: the view falls back to its idle animation
        }
    }

    override fun onDestroy() {
        isRunning = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        try {
            edgeView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
