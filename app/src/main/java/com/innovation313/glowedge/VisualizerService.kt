package com.innovation313.glowedge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Runs the overlay glow. Reads the output audio via the Visualizer API, classifies it as
 * music/naat vs speech, and (in music-only mode) shows the glow only for music. On silence
 * or normal talking the glow stays dark. Also flashes on notifications when enabled.
 */
class VisualizerService : Service() {

    companion object {
        const val CHANNEL_ID = "glowedge"
        const val ACTION_STOP = "com.innovation313.glowedge.STOP"
        const val ACTION_TEST = "com.innovation313.glowedge.TEST"
        const val ACTION_NOTIF = "com.innovation313.glowedge.NOTIF"
        const val EXTRA_COLOR = "color"
        @Volatile var isRunning = false
    }

    private var wm: WindowManager? = null
    private var view: EdgeView? = null
    private var visualizer: Visualizer? = null

    private var musicOnly = true
    private var sensitivity = 4
    private val bandMax = FloatArray(32) { 0.05f }
    private val prevBands = FloatArray(32)
    private val fluxHist = FloatArray(16)
    private var fluxIdx = 0
    private var musicScore = 0f
    private var sustain = 0

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == ACTION_NOTIF) {
                val col = i.getIntExtra(EXTRA_COLOR, 0)
                if (col != 0) view?.flash(col)
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundNotification()
        addOverlay()
        applySettings()
        startVisualizer()
        val f = IntentFilter(ACTION_NOTIF)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(notifReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(notifReceiver, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TEST -> { view?.flash(Settings.theme(this).start); return START_STICKY }
        }
        applySettings()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig); resizeOverlay()
    }

    private fun applySettings() {
        musicOnly = Settings.musicOnly(this)
        sensitivity = Settings.sensitivity(this)
        val t = Settings.theme(this)
        view?.apply(
            Settings.styleId(this), t.start, t.end, t.rainbow,
            Settings.thickness(this).toFloat(),
            Settings.speed(this) / 10f,
            Settings.intensity(this) / 10f,
            Settings.batterySaver(this)
        )
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GlowEdge", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1,
            Intent(this, VisualizerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_glow)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(open).addAction(0, getString(R.string.stop), stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun addOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        view = EdgeView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val b = wm!!.currentWindowMetrics.bounds; p.width = b.width(); p.height = b.height()
            }
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 28)
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try { wm?.addView(view, p) } catch (_: Exception) { stopSelf() }
    }

    private fun resizeOverlay() {
        val v = view ?: return; val w = wm ?: return
        try {
            val p = v.layoutParams as WindowManager.LayoutParams
            if (Build.VERSION.SDK_INT >= 30) {
                val b = w.currentWindowMetrics.bounds; p.width = b.width(); p.height = b.height()
            }
            w.updateViewLayout(v, p); v.invalidate()
        } catch (_: Exception) {}
    }

    private fun startVisualizer() {
        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                        if (fft == null || fft.size < 256) return
                        val n = 32; val out = FloatArray(n); val maxBin = (fft.size / 2 - 1).coerceAtMost(220)
                        var sum = 0f
                        for (i in 0 until n) {
                            val start = (2.0 * Math.pow(110.0, i / n.toDouble())).toInt().coerceIn(2, maxBin)
                            val end = (2.0 * Math.pow(110.0, (i + 1) / n.toDouble())).toInt().coerceIn(start + 1, maxBin + 1)
                            var peak = 0f
                            for (bin in start until end) {
                                val re = fft[bin * 2].toFloat(); val im = fft[bin * 2 + 1].toFloat()
                                val m = sqrt(re * re + im * im) / 128f; if (m > peak) peak = m
                            }
                            bandMax[i] = kotlin.math.max(bandMax[i] * 0.994f, 0.045f)
                            if (peak > bandMax[i]) bandMax[i] = peak
                            val norm = (peak / bandMax[i]).coerceIn(0f, 1f)
                            out[i] = norm * norm * (3f - 2f * norm); sum += out[i]
                        }
                        var bass = 0f; for (i in 0 until 10) bass += out[i]
                        val raw = ((bass / 10f) * 0.7f + (sum / n) * 0.5f).coerceIn(0f, 1f)
                        val gated = if (!musicOnly || isMusical(out, raw)) raw else 0f
                        view?.feed(gated, out)
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Exception) {
            // Device blocks audio capture. Glow simply stays dark until audio is available.
        }
    }

    /** True when the audio looks like music/naat, false for silence and normal speech. */
    private fun isMusical(bands: FloatArray, level: Float): Boolean {
        val n = bands.size
        var active = 0; for (b in bands) if (b > 0.10f) active++
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
        var flux = 0f
        for (i in 0 until n) { val d = bands[i] - prevBands[i]; if (d > 0) flux += d; prevBands[i] = bands[i] }
        fluxHist[fluxIdx] = flux; fluxIdx = (fluxIdx + 1) % fluxHist.size
        var mean = 0f; for (f in fluxHist) mean += f; mean /= fluxHist.size
        var varc = 0f; for (f in fluxHist) varc += (f - mean) * (f - mean); varc /= fluxHist.size
        val steadiness = 1f / (1f + varc * 6f)
        if (level > 0.05f) sustain++ else sustain = 0
        val sustained = sustain > 5

        val s = sensitivity / 10f
        val spreadNeed = 0.42f - s * 0.18f
        val balanceNeed = 0.55f - s * 0.30f
        val steadyNeed = 0.50f - s * 0.26f

        // Reject normal speech: mid-dominant + low spread
        if (balance < 0.35f && spread < 0.34f) {
            musicScore += (0f - musicScore) * 0.05f
            return musicScore > 0.40f
        }
        var cues = 0
        if (spread >= spreadNeed) cues++
        if (balance >= balanceNeed) cues++
        if (steadiness >= steadyNeed) cues++
        if (sustained) cues++
        val musical = level > 0.07f && cues >= 3
        val rise = 0.07f + s * 0.04f
        musicScore += ((if (musical) 1f else 0f) - musicScore) * (if (musical) rise else 0.04f)
        return musicScore > 0.40f
    }

    override fun onDestroy() {
        isRunning = false
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
        try { visualizer?.enabled = false; visualizer?.release() } catch (_: Exception) {}
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
