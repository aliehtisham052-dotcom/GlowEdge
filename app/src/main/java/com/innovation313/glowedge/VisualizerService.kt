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
    private val bandMax = FloatArray(32) { 0.05f }

    // ---- Music / melody detection state ----
    private var musicScore = 0f          // 0 = speech/noise, 1 = clearly musical
    private var prevLevel = 0f
    private var sustainFrames = 0
    private var musicOnly = true
    private var sensitivity = 5
    private val fluxHistory = FloatArray(16)
    private var fluxIndex = 0
    private val prevBands = FloatArray(32)

    private val notifReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                GlowNotificationService.ACTION_NOTIFICATION_GLOW -> {
                    val color = intent.getIntExtra(GlowNotificationService.EXTRA_COLOR, 0)
                    if (color != 0) edgeView?.triggerNotificationFlash(color)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    val prefs = getSharedPreferences("glowedge_prefs", MODE_PRIVATE)
                    if (prefs.getBoolean("charging_glow", true)) {
                        edgeView?.triggerNotificationFlash(android.graphics.Color.parseColor("#00E676"))
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    val prefs = getSharedPreferences("glowedge_prefs", MODE_PRIVATE)
                    if (prefs.getBoolean("charging_glow", true)) {
                        edgeView?.triggerNotificationFlash(android.graphics.Color.parseColor("#FF9100"))
                    }
                }
                Intent.ACTION_HEADSET_PLUG,
                "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    // Audio route changed (wired/earbuds/Bluetooth) - reattach the visualizer
                    restartVisualizer()
                }
            }
        }
    }
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
        registerAudioRouteCallback()
        val filter = android.content.IntentFilter(GlowNotificationService.ACTION_NOTIFICATION_GLOW)
        filter.addAction(Intent.ACTION_POWER_CONNECTED)
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        filter.addAction(Intent.ACTION_HEADSET_PLUG)
        filter.addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(notifReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(notifReceiver, filter)
        }
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
        musicOnly = ProfileManager.musicOnly(this)
        sensitivity = ProfileManager.sensitivity(this)
        val theme = ProfileManager.theme(this)
        edgeView?.applySettings(
            ProfileManager.style(this),
            theme.colorStart,
            theme.colorEnd,
            theme.rainbow,
            ProfileManager.thickness(this).toFloat(),
            ProfileManager.speed(this) / 10f,
            ProfileManager.intensity(this) / 10f,
            ProfileManager.batterySaver(this)
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Use the real display size so the overlay covers the area behind the navigation bar too
        try {
            val metrics = resources.displayMetrics
            val realH = metrics.heightPixels
            val realW = metrics.widthPixels
            val wm = windowManager
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                val b = wm.currentWindowMetrics.bounds
                params.width = b.width()
                params.height = b.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealMetrics(dm)
                params.width = dm.widthPixels
                params.height = dm.heightPixels
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            windowManager?.addView(edgeView, params)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    /**
     * Heuristic music/naat/melody detector. Returns true when the audio looks
     * musical (energy spread across bands, bass+treble present, sustained),
     * false for plain speech / notification blips / random noise.
     */
    private fun isMusical(bands: FloatArray, level: Float): Boolean {
        val n = bands.size

        // 1) Spectral spread: how many bands carry real energy
        var active = 0
        for (b in bands) if (b > 0.10f) active++
        val spread = active / n.toFloat()

        // 2) Band balance: music has bass + treble; speech is mid-dominant (300-3400 Hz)
        var bass = 0f; var mid = 0f; var treble = 0f
        for (i in 0 until n) {
            when {
                i < n / 3 -> bass += bands[i]
                i < 2 * n / 3 -> mid += bands[i]
                else -> treble += bands[i]
            }
        }
        val balance = (bass + treble) / (mid + 0.001f)

        // 3) Spectral flux: how much the spectrum changes frame to frame.
        //    Music/naat has steady, rhythmic flux; speech is bursty and irregular.
        var flux = 0f
        for (i in 0 until n) {
            val d = bands[i] - prevBands[i]
            if (d > 0) flux += d
            prevBands[i] = bands[i]
        }
        fluxHistory[fluxIndex] = flux
        fluxIndex = (fluxIndex + 1) % fluxHistory.size
        var mean = 0f
        for (f in fluxHistory) mean += f
        mean /= fluxHistory.size
        var varc = 0f
        for (f in fluxHistory) varc += (f - mean) * (f - mean)
        varc /= fluxHistory.size
        // Low variance in flux = steady rhythm (musical). High variance = choppy speech.
        val steadiness = 1f / (1f + varc * 6f)

        // 4) Sustain: musical/vocal audio keeps energy going (naat sustains notes);
        //    speech has frequent micro-gaps between words.
        if (level > 0.05f) sustainFrames++ else sustainFrames = 0
        val sustained = sustainFrames > 5

        prevLevel = level

        // Sensitivity eases thresholds (1 strict .. 10 loose)
        val s = sensitivity / 10f
        val spreadNeed = 0.40f - s * 0.20f
        val balanceNeed = 0.50f - s * 0.32f
        val steadyNeed = 0.45f - s * 0.28f

        // Score each cue, combine. Naat (vocal, melodic, sustained, steady) passes via
        // sustain + steadiness even when bass is light; instrumental music passes via balance+spread.
        var cues = 0
        if (spread >= spreadNeed) cues++
        if (balance >= balanceNeed) cues++
        if (steadiness >= steadyNeed) cues++
        if (sustained) cues++

        val looksMusical = level > 0.06f && cues >= 3

        val rise = 0.08f + s * 0.05f
        val fall = 0.03f
        val target = if (looksMusical) 1f else 0f
        musicScore += (target - musicScore) * (if (target > musicScore) rise else fall)

        return musicScore > 0.35f
    }

    private fun restartVisualizer() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null
        // Small delay so the new audio route is fully established before re-attaching
        edgeView?.postDelayed({ startVisualizer() }, 700)
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
                        if (fft == null || fft.size < 256) return
                        val bandCount = 32
                        val bands = FloatArray(bandCount)
                        val maxBin = (fft.size / 2 - 1).coerceAtMost(220)
                        var sum = 0f
                        for (i in 0 until bandCount) {
                            // Logarithmic mapping: low bands = bass, high bands = treble,
                            // matching how the human ear hears music
                            val start = (2.0 * Math.pow(110.0, i / bandCount.toDouble()))
                                .toInt().coerceIn(2, maxBin)
                            val end = (2.0 * Math.pow(110.0, (i + 1) / bandCount.toDouble()))
                                .toInt().coerceIn(start + 1, maxBin + 1)
                            var peak = 0f
                            for (bin in start until end) {
                                val re = fft[bin * 2].toFloat()
                                val im = fft[bin * 2 + 1].toFloat()
                                val m = sqrt(re * re + im * im) / 128f
                                if (m > peak) peak = m
                            }
                            // Per-band auto gain: quiet songs and loud songs both dance fully
                            bandMax[i] = kotlin.math.max(bandMax[i] * 0.994f, 0.045f)
                            if (peak > bandMax[i]) bandMax[i] = peak
                            val norm = (peak / bandMax[i]).coerceIn(0f, 1f)
                            bands[i] = norm * norm * (3f - 2f * norm) // smoothstep for natural feel
                            sum += bands[i]
                        }
                        // Overall pulse driven mostly by bass (first third of bands)
                        var bassSum = 0f
                        for (i in 0 until 10) bassSum += bands[i]
                        val rawLevel = ((bassSum / 10f) * 0.7f + (sum / bandCount) * 0.5f).coerceIn(0f, 1f)

                        // ---- Music vs speech detection ----
                        val musical = isMusical(bands, rawLevel)
                        val gatedLevel = if (!musicOnly || musical) rawLevel else 0f
                        edgeView?.setAudioData(gatedLevel, bands)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) {
            // Restricted device: the view falls back to its idle animation
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlaySize()
    }

    private fun updateOverlaySize() {
        val view = edgeView ?: return
        val wm = windowManager ?: return
        try {
            val params = view.layoutParams as WindowManager.LayoutParams
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.currentWindowMetrics.bounds
                params.width = b.width()
                params.height = b.height()
            } else {
                @Suppress("DEPRECATION")
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(dm)
                params.width = dm.widthPixels
                params.height = dm.heightPixels
            }
            wm.updateViewLayout(view, params)
            view.requestLayout()
            view.invalidate()
        } catch (_: Exception) {
        }
    }

    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    private fun registerAudioRouteCallback() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val cb = object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
                    restartVisualizer()
                }
                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
                    restartVisualizer()
                }
            }
            am.registerAudioDeviceCallback(cb, null)
            audioDeviceCallback = cb
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) {}
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
