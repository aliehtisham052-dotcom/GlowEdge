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
    private var musicOnly = true
    private var sensitivity = 5
    // Running history of how tonal/harmonic recent frames were (music sustains tonality;
    // speech only touches it briefly on vowels).
    private val tonalHistory = FloatArray(16)
    private var tonalIndex = 0

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
        updateNotification()
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

    /**
     * Music/naat detector with real harmonic analysis.
     *
     * The key discriminator is the Harmonic Product Spectrum (HPS): sung or played
     * notes have a clear fundamental frequency plus overtones at integer multiples
     * (2x, 3x, 4x...). Multiplying the spectrum by downsampled copies of itself makes
     * that harmonic stack reinforce into one sharp peak. Speech — even fluent, loud
     * speech — is inharmonic and noisy, so its HPS stays flat with no dominant peak.
     * This is what earlier energy-only versions couldn't see: they measured how the
     * sound was *spread*, not whether it was actually *tonal*.
     *
     * A tonal frame is confirmed when the HPS has a strong, isolated peak (high
     * peak-to-average ratio) and that peak sits in a plausible musical pitch range.
     * We track how consistently recent frames are tonal, so a single word landing on
     * a vowel doesn't trigger, but a held/sung line does. The older spread/balance
     * cues are kept only as a secondary confirmation, not the main gate.
     */
    private fun isMusical(bands: FloatArray, level: Float, spectrum: FloatArray): Boolean {
        val n = bands.size

        // --- Secondary cues (spectral shape), kept as light support ---
        var active = 0
        for (b in bands) if (b > 0.10f) active++
        val spread = active / n.toFloat()

        var bass = 0f; var mid = 0f; var treble = 0f
        for (i in 0 until n) {
            when {
                i < n / 3 -> bass += bands[i]
                i < 2 * n / 3 -> mid += bands[i]
                else -> treble += bands[i]
            }
        }
        val balance = (bass + treble) / (mid + 0.001f)

        // --- PRIMARY cue: Harmonic Product Spectrum ---
        val tonal = harmonicScore(spectrum)          // 0..1, how tonal/harmonic this frame is
        tonalHistory[tonalIndex] = tonal
        tonalIndex = (tonalIndex + 1) % tonalHistory.size
        var tonalMean = 0f
        for (t in tonalHistory) tonalMean += t
        tonalMean /= tonalHistory.size

        val s = sensitivity / 10f
        // Harmonic threshold: stricter at low sensitivity, looser at high.
        val tonalNeed = 0.55f - s * 0.22f

        // Music/naat = a sustained tonal signal (harmonics present across recent frames).
        // Speech briefly touches tonal on vowels but its running mean stays low.
        val harmonicallyMusical = tonalMean >= tonalNeed
        // Secondary confirmation catches busy instrumental music whose fundamental is
        // muddled but which is clearly wide-band and balanced.
        val texturallyMusical = spread >= (0.55f - s * 0.15f) && balance >= (0.75f - s * 0.25f)

        val looksMusical = level > 0.07f && (harmonicallyMusical || texturallyMusical)

        val rise = 0.06f + s * 0.03f
        val fall = 0.05f
        val target = if (looksMusical) 1f else 0f
        musicScore += (target - musicScore) * (if (target > musicScore) rise else fall)

        return musicScore > 0.45f
    }

    /**
     * Harmonic Product Spectrum score in [0,1]. Downsamples the magnitude spectrum by
     * 2x, 3x and 4x and multiplies them together; a true fundamental with overtones
     * produces one tall spike. We return how strongly that spike stands out from the
     * average (peak-to-average ratio, squashed to 0..1), gated to a musical pitch band.
     */
    private fun harmonicScore(spectrum: FloatArray): Float {
        val len = spectrum.size
        if (len < 32) return 0f

        // Only look where musical fundamentals live (skip DC/rumble and the very top).
        val lo = 2
        val hi = len / 4          // 4x downsample must stay in-bounds
        if (hi <= lo + 4) return 0f

        var peak = 0f
        var peakIdx = lo
        var total = 0f
        var count = 0
        for (i in lo until hi) {
            val h = spectrum[i] * spectrum[i * 2] * spectrum[i * 3] * spectrum[i * 4]
            total += h
            count++
            if (h > peak) { peak = h; peakIdx = i }
        }
        if (peak <= 0f || count == 0) return 0f
        val avg = total / count
        if (avg <= 1e-9f) return 0f

        // Peak-to-average: flat (speech/noise) ~ small; sharp (tonal) ~ large.
        val ratio = peak / avg

        // Require the peak to also be a real local maximum (not a lone spiky bin).
        val leftOk = peakIdx <= lo || spectrum[peakIdx] >= spectrum[peakIdx - 1] * 0.6f
        val rightOk = peakIdx >= hi - 1 || spectrum[peakIdx] >= spectrum[peakIdx + 1] * 0.6f
        if (!leftOk || !rightOk) return 0f

        // Squash ratio into 0..1 (ratios above ~40 are firmly tonal).
        return (ratio / 40f).coerceIn(0f, 1f)
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

                        // ---- Music vs speech detection (now harmonic-aware) ----
                        val musical = isMusical(bands, rawLevel, spectrum)
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
