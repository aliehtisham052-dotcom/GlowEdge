package com.innovation313.glowedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A live wallpaper that renders GlowEdge's signature glow design plus glanceable,
 * always-current info: the time, the date, and a live battery reading. It reuses the
 * currently selected colour theme, so the wallpaper always matches the app.
 *
 * Redraws once a minute (and immediately on battery/time changes), not continuously,
 * to stay battery-friendly — consistent with the rest of the app.
 */
class GlowLiveWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = GlowEngine()

    inner class GlowEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateFmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        private val dayFmt = SimpleDateFormat("EEEE", Locale.US)

        private val RAINBOW = intArrayOf(
            Color.parseColor("#FF3B5C"), Color.parseColor("#FFD93B"), Color.parseColor("#3BE885"),
            Color.parseColor("#3BD4FF"), Color.parseColor("#B93BFF"), Color.parseColor("#FF3B5C")
        )

        private val redrawRunnable = Runnable { draw() }

        private val updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { draw() }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)          // fires every minute
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            registerReceiver(updateReceiver, filter)
        }

        override fun onVisibilityChanged(vis: Boolean) {
            visible = vis
            if (vis) draw() else handler.removeCallbacks(redrawRunnable)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, w: Int, h: Int) {
            width = w; height = h; draw()
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(redrawRunnable)
            try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        }

        private fun batteryInfo(): Pair<Int, Boolean> {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            return Pair(level.coerceIn(0, 100), charging)
        }

        private fun draw() {
            if (!visible || width == 0 || height == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
            // Schedule the next minute tick as a fallback (ACTION_TIME_TICK also drives it).
            handler.removeCallbacks(redrawRunnable)
            if (visible) handler.postDelayed(redrawRunnable, 60_000L)
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val theme = ProfileManager.theme(this@GlowLiveWallpaper)
            val c1 = theme.colorStart
            val c2 = theme.colorEnd
            val beam = if (theme.rainbow) RAINBOW[3] else c1

            drawField(canvas, w, h, c2)
            drawEdgeShine(canvas, w, h, c1, c2)
            drawInfo(canvas, w, h, theme, beam)
        }

        private fun drawField(canvas: Canvas, w: Float, h: Float, warm: Int) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#05070F"),
                    Color.parseColor("#0A1128"),
                    Color.parseColor("#0C1430"),
                    blend(Color.parseColor("#0C1430"), warm, 0.22f)
                ),
                floatArrayOf(0f, 0.34f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        private fun drawEdgeShine(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
            val colors = if (ProfileManager.theme(this@GlowLiveWallpaper).rainbow) RAINBOW
                         else intArrayOf(c1, c2, c1, c2, c1)
            val thickness = w * 0.018f
            val inset = thickness * 1.6f
            val rect = RectF(inset, inset, w - inset, h - inset)
            val corner = w * 0.11f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = SweepGradient(w / 2f, h / 2f, colors, null)

            paint.strokeWidth = thickness * 2.4f
            paint.maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL)
            paint.alpha = 150
            canvas.drawRoundRect(rect, corner, corner, paint)

            paint.strokeWidth = thickness
            paint.maskFilter = BlurMaskFilter(thickness * 1.4f, BlurMaskFilter.Blur.NORMAL)
            paint.alpha = 235
            canvas.drawRoundRect(rect, corner, corner, paint)

            paint.maskFilter = null
            paint.strokeWidth = thickness * 0.28f
            paint.alpha = 255
            canvas.drawRoundRect(rect, corner, corner, paint)
        }

        /** Time, date + day, and a live battery reading — the glanceable info block. */
        private fun drawInfo(canvas: Canvas, w: Float, h: Float, theme: Profile, beam: Int) {
            val now = Date()
            val leftPad = w * 0.10f

            // Big time
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            timePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            timePaint.textSize = w * 0.20f
            timePaint.color = Color.WHITE
            timePaint.setShadowLayer(w * 0.03f, 0f, 0f, withAlpha(beam, 120))
            canvas.drawText(timeFmt.format(now), leftPad, h * 0.20f, timePaint)

            // Date row with a small theme dot
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            dotPaint.color = theme.colorStart
            dotPaint.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(theme.colorStart, 160))
            canvas.drawCircle(leftPad + w * 0.012f, h * 0.265f, w * 0.014f, dotPaint)

            val datePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            datePaint.textSize = w * 0.048f
            datePaint.color = theme.colorStart
            canvas.drawText(dateFmt.format(now).uppercase(), leftPad + w * 0.045f, h * 0.282f, datePaint)

            val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            dayPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            dayPaint.textSize = w * 0.036f
            dayPaint.color = withAlpha(Color.WHITE, 150)
            dayPaint.letterSpacing = 0.08f
            canvas.drawText(dayFmt.format(now).uppercase(), leftPad + w * 0.045f, h * 0.318f, dayPaint)

            // Battery row
            val (level, charging) = batteryInfo()
            val batteryColor = when {
                charging -> theme.colorEnd
                level <= 15 -> Color.parseColor("#FF5252")
                else -> theme.colorStart
            }
            dotPaint.color = batteryColor
            dotPaint.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(batteryColor, 160))
            canvas.drawCircle(leftPad + w * 0.012f, h * 0.365f, w * 0.014f, dotPaint)

            val battPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            battPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            battPaint.textSize = w * 0.048f
            battPaint.color = batteryColor
            val battLabel = "BATTERY $level%" + if (charging) "  \u26A1" else ""
            canvas.drawText(battLabel, leftPad + w * 0.045f, h * 0.382f, battPaint)

            val statePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            statePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            statePaint.textSize = w * 0.036f
            statePaint.color = withAlpha(batteryColor, 150)
            statePaint.letterSpacing = 0.08f
            val stateLabel = if (charging) "CHARGING" else if (level <= 15) "LOW" else "IDLE"
            canvas.drawText(stateLabel, leftPad + w * 0.045f, h * 0.418f, statePaint)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        private fun blend(a: Int, b: Int, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
            )
        }
    }
}
