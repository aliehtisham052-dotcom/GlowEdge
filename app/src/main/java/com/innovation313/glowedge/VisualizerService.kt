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
        const val ACTION_TEST = "com.innovation313.glowedge.TEST"
        @Volatile
        var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private val bandMax = FloatArray(32) { 0.05f }
    private var wasStartedByUser = false

    // ---- Music / melody detection state ----
    private var musicScore = 0f          // 0 = speech/noise, 1 = clearly musical
    private var prevLevel = 0f
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
                    val repeat = intent.getBooleanExtra("repeat", false)
                    if (color != 0) {
                        if (repeat) edgeView?.triggerCallFlash(color)
                        else edgeView?.triggerNotificationFlash(color)
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
        if (ProfileManager.intro(this)) edgeView?.startIntro()
        startVisualizer()
        registerAudioRouteCallback()
        val filter = android.content.IntentFilter(GlowNotificationService.ACTION_NOTIFICATION_GLOW)
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
        if (intent?.action == ACTION_TEST) {
            wasStartedByUser = true
            edgeView?.forceTestGlow()
            return START_STICKY
        }
        wasStartedByUser = true
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
            ProfileManager.batterySaver(this),
            ProfileManager.personalText(this),
            ProfileManager.personalTextEnabled(this),
            ProfileManager.personalTextColor(this)
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
     * Heuristic music/naat/melody detector. Returns true only when the audio
     * simultaneously looks musical on every measure — wide spectral spread, real
     * bass+treble balance, AND steady frame-to-frame flux. Returns false for plain
     * speech, bayan/lecture, notification blips, or random noise.
     *
     * RESET (this version): earlier attempts patched individual thresholds and added
     * a "long sustained vocal" bypass so a cappella naat without bass could still pass.
     * That bypass turned out to be the main leak — ordinary fluent conversation could
     * trigger it just by talking continuously for under a second. There is no bypass
     * left now: every cue must be true at the same time, every single frame. Ordinary
     * speech is inherently narrow-band and mid-dominant, so it structurally cannot
     * satisfy all three cues together. The trade-off, stated plainly: a pure,
     * unaccompanied a cappella naat with no bass at all may occasionally be missed.
     * That is the deliberate cost of eliminating false positives on conversation.
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

        // 3) Spectral flux steadiness: how much the spectrum changes frame to frame.
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
        val steadiness = 1f / (1f + varc * 6f)

        prevLevel = level

        // Sensitivity eases thresholds (1 strict .. 10 loose)
        val s = sensitivity / 10f
        val spreadNeed = 0.38f - s * 0.14f
        val balanceNeed = 0.50f - s * 0.22f

        // Two paths to "musical" — real audio doesn't hold every cue steady at once:
        // Path A: real spectral width AND bass+treble balance together. This is the
        //   most reliable signal, because ordinary speech structurally lacks both at
        //   once — it's the main gate for typical music/naat with instrumentation.
        // Path B: very high steadiness (a held/rhythmic vocal or drone) with at least
        //   some spread, even if balance is weak — covers sparse a cappella naat/zikr
        //   that has little bass but sustains a steady tone.
        val pathA = spread >= spreadNeed && balance >= balanceNeed
        val pathB = steadiness >= 0.62f && spread >= spreadNeed * 0.75f
        val coreMusical = pathA || pathB
        val looksMusical = level > 0.07f && coreMusical

        val rise = 0.06f + s * 0.03f
        val fall = 0.05f  // gentle enough that a brief pause/quiet verse doesn't kill it
        val target = if (looksMusical) 1f else 0f
        musicScore += (target - musicScore) * (if (target > musicScore) rise else fall)

        return musicScore > 0.45f
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
                }, Visualizer.getMaxCaptureRate() / 4, false, true)
                enabled = true
            }
        } catch (_: Exception) {
            // Device blocks audio capture. Only show a demo glow if music-only mode is OFF,
            // so users who want music-only keep a dark screen until real music plays.
            if (!musicOnly) edgeView?.setDemoMode(true)
        }

        // No RECORD_AUDIO permission: demo only when music-only is OFF
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!musicOnly) edgeView?.setDemoMode(true)
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
