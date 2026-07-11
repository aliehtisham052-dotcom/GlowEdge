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

            drawField(canvas, w, h, c2)
            drawEdgeShine(canvas, w, h, c1, c2)
            drawInfo(canvas, w, h, theme)
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
        private fun drawInfo(canvas: Canvas, w: Float, h: Float, theme: Profile) {
            // NOTE: we deliberately do NOT draw the time or date here. On the lock screen
            // the phone draws its own clock and date on top of the wallpaper, so drawing
            // ours too would overlap and look messy. We only show a battery ring — which
            // the system does not show — placed low and centered so it never clashes with
            // the system clock, notifications or the fingerprint/camera area.
            val (level, charging) = batteryInfo()
            val batteryColor = when {
                charging -> theme.colorEnd
                level <= 15 -> Color.parseColor("#FF5252")
                else -> theme.colorStart
            }
            drawBatteryRing(canvas, w, h, level, batteryColor, charging)
        }

        /**
         * A refined circular battery ring: a faint full track with a bright arc filling
         * to the current level, and the percentage centred inside. Placed in the lower
         * third and centered so it never overlaps the system clock or notifications.
         * Gives a premium, glanceable read of the battery the system doesn't provide.
         */
        private fun drawBatteryRing(canvas: Canvas, w: Float, h: Float, level: Int, color: Int, charging: Boolean) {
            val cx = w * 0.5f
            val cy = h * 0.60f
            val r = w * 0.13f
            val ring = RectF(cx - r, cy - r, cx + r, cy + r)

            // Faint full track.
            val track = Paint(Paint.ANTI_ALIAS_FLAG)
            track.style = Paint.Style.STROKE
            track.strokeCap = Paint.Cap.ROUND
            track.strokeWidth = w * 0.018f
            track.color = withAlpha(Color.WHITE, 35)
            canvas.drawArc(ring, 0f, 360f, false, track)

            // Bright progress arc (starts at top, clockwise).
            val prog = Paint(Paint.ANTI_ALIAS_FLAG)
            prog.style = Paint.Style.STROKE
            prog.strokeCap = Paint.Cap.ROUND
            prog.strokeWidth = w * 0.018f
            prog.color = color
            prog.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(color, 150))
            val sweep = 360f * (level / 100f)
            canvas.drawArc(ring, -90f, sweep, false, prog)

            // Percentage in the centre.
            val pct = Paint(Paint.ANTI_ALIAS_FLAG)
            pct.textAlign = Paint.Align.CENTER
            pct.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            pct.textSize = w * 0.07f
            pct.color = Color.WHITE
            canvas.drawText("$level", cx, cy + w * 0.01f, pct)

            val pctSign = Paint(Paint.ANTI_ALIAS_FLAG)
            pctSign.textAlign = Paint.Align.CENTER
            pctSign.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            pctSign.textSize = w * 0.032f
            pctSign.color = withAlpha(color, 200)
            val innerLabel = if (charging) "\u26A1" else "%"
            canvas.drawText(innerLabel, cx, cy + w * 0.055f, pctSign)
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
