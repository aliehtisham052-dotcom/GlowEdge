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
import android.os.SystemClock
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

    // ---- Music gate ----
    private var musicOnly = true

    // MediaSession music check is cached ~1s so we don't query it on every audio frame.
    private var lastMusicCheckTime = 0L
    private var lastMusicPlaying = false
    private fun isMusicPlayingCached(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMusicCheckTime > 1000L) {
            lastMusicCheckTime = now
            lastMusicPlaying = MediaPlaybackDetector.isMusicPlaying(this)
        }
        return lastMusicPlaying
    }

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

    // ---- Call Glow: flash the edge glow on an incoming call. Uses Android's telephony
    // call state, which is exact (RINGING/OFFHOOK/IDLE) — not a guess like audio. ----
    private var telephonyManager: android.telephony.TelephonyManager? = null
    private var lastCallState = android.telephony.TelephonyManager.CALL_STATE_IDLE
    private val phoneStateListener = object : android.telephony.PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (state == android.telephony.TelephonyManager.CALL_STATE_RINGING &&
                lastCallState != android.telephony.TelephonyManager.CALL_STATE_RINGING) {
                if (ProfileManager.callGlow(this@VisualizerService)) {
                    val theme = ProfileManager.theme(this@VisualizerService)
                    edgeView?.triggerCallFlash(theme.colorStart)
                }
            }
            lastCallState = state
        }
    }

    private fun registerCallListener() {
        if (!ProfileManager.callGlow(this)) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_PHONE_STATE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        try {
            telephonyManager = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            telephonyManager?.listen(
                phoneStateListener,
                android.telephony.PhoneStateListener.LISTEN_CALL_STATE
            )
        } catch (_: Exception) {}
    }

    private fun unregisterCallListener() {
        try {
            telephonyManager?.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_NONE)
        } catch (_: Exception) {}
        telephonyManager = null
    }

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
        registerCallListener()
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
        // Re-sync the call listener in case Call Glow was just toggled or permission granted.
        unregisterCallListener()
        registerCallListener()
        updateNotification()
        return START_STICKY
    }

    private fun applyCurrentSettings() {
        musicOnly = ProfileManager.musicOnly(this)
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
            ProfileManager.glowEdges(this)
        )
    }

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GlowEdge", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    /** Rebuilds the notification so its Force Glow label reflects the current mode. */
    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .build()
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

                        // Raw per-bin magnitude spectrum, kept for harmonic analysis
                        // (Harmonic Product Spectrum needs the full-resolution spectrum,
                        // not the coarse 32-band version used for the visuals).
                        val specLen = (fft.size / 2).coerceAtMost(256)
                        val spectrum = FloatArray(specLen)
                        for (bin in 0 until specLen) {
                            val re = fft[bin * 2].toFloat()
                            val im = fft[bin * 2 + 1].toFloat()
                            spectrum[bin] = sqrt(re * re + im * im) / 128f
                        }

                        for (i in 0 until bandCount) {
                            // Logarithmic mapping: low bands = bass, high bands = treble,
                            // matching how the human ear hears music
                            val start = (2.0 * Math.pow(110.0, i / bandCount.toDouble()))
                                .toInt().coerceIn(2, maxBin)
                            val end = (2.0 * Math.pow(110.0, (i + 1) / bandCount.toDouble()))
                                .toInt().coerceIn(start + 1, maxBin + 1)
                            var peak = 0f
                            for (bin in start until end) {
                                val m = if (bin < specLen) spectrum[bin] else 0f
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

                        // ---- Music gate ----
                        // When Music Only is on, we use Android's MediaSession state (exact:
                        // reported by the playing app itself) instead of guessing from the mic.
                        // The mic still shapes the glow; MediaSession decides whether it lights up.
                        val gatedLevel = if (!musicOnly || isMusicPlayingCached()) rawLevel else 0f
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
        unregisterCallListener()
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
