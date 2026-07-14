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
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * GlowEdge's animated live wallpaper: a flowing edge glow, drifting sparkles, and a
 * refined battery ring, all in the app's currently selected colour theme.
 *
 * Animation runs at ~30fps ONLY while the wallpaper is actually visible; the moment it
 * is hidden (screen off, app in front) all drawing stops, so it costs nothing in the
 * background. That's the trade for the motion — it's deliberately lightweight: a small
 * fixed number of sparkles, no bitmaps, no allocation inside the frame loop.
 */
class GlowLiveWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = GlowEngine()

    inner class GlowEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0

        private val FRAME_MS = 33L        // ~30fps while visible
        private var phase = 0f            // drives the flowing edge gradient
        private var startTime = SystemClock.elapsedRealtime()

        // ---- Sparkles: soft points of light drifting upward and twinkling ----
        private val SPARKLE_COUNT = 46
        private val sx = FloatArray(SPARKLE_COUNT)      // 0..1 across width
        private val sy = FloatArray(SPARKLE_COUNT)      // 0..1 down height
        private val sSize = FloatArray(SPARKLE_COUNT)   // relative radius
        private val sSpeed = FloatArray(SPARKLE_COUNT)  // upward drift
        private val sPhase = FloatArray(SPARKLE_COUNT)  // twinkle offset
        private val sDrift = FloatArray(SPARKLE_COUNT)  // gentle sideways sway
        private var sparklesReady = false

        // Reused paints and objects — never allocate inside the frame loop.
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val haloRect = RectF()
        private val hsvTmp = FloatArray(3)
        private val edgeMatrix = android.graphics.Matrix()
        private val edgePath = android.graphics.Path()
        private val pathMeasure = android.graphics.PathMeasure()
        private val posTmp = FloatArray(2)

        // Now Playing cache — the media lookup is too costly to run every frame.
        private var lastTrack: String? = null
        private var lastTrackCheck = 0L

        private val frameRunnable = Runnable { draw() }

        private val updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { /* battery redraws next frame */ }
        }

        private fun initSparkles() {
            val r = Random(313)   // fixed seed: same pleasing layout every time
            for (i in 0 until SPARKLE_COUNT) {
                sx[i] = r.nextFloat()
                sy[i] = r.nextFloat()
                sSize[i] = 0.5f + r.nextFloat() * 1.6f
                sSpeed[i] = 0.010f + r.nextFloat() * 0.030f
                sPhase[i] = r.nextFloat() * 6.283f
                sDrift[i] = (r.nextFloat() - 0.5f) * 0.5f
            }
            sparklesReady = true
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            initSparkles()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            registerReceiver(updateReceiver, filter)
        }

        override fun onVisibilityChanged(vis: Boolean) {
            visible = vis
            if (vis) {
                startTime = SystemClock.elapsedRealtime()
                draw()
            } else {
                handler.removeCallbacks(frameRunnable)   // stop all work when hidden
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, w: Int, h: Int) {
            width = w; height = h; draw()
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(frameRunnable)
            try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        }

        /** Level, charging state, and temperature in Celsius (-1 if unreported). */
        private fun batteryInfo(): Triple<Int, Boolean, Float> {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            // EXTRA_TEMPERATURE is in tenths of a degree Celsius.
            val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val tempC = if (rawTemp > 0) rawTemp / 10f else -1f

            return Triple(level.coerceIn(0, 100), charging, tempC)
        }

        private fun draw() {
            if (!visible || width == 0 || height == 0) return
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
            handler.removeCallbacks(frameRunnable)
            if (visible) handler.postDelayed(frameRunnable, FRAME_MS)
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val theme = ProfileManager.theme(this@GlowLiveWallpaper)
            val c1 = theme.colorStart
            val c2 = theme.colorEnd
            val t = (SystemClock.elapsedRealtime() - startTime) / 1000f

            phase += 0.55f            // flowing gradient rotation

            drawField(canvas, w, h, c2)
            drawSparkles(canvas, w, h, c1, c2, t, theme.rainbow)
            drawFlowingEdge(canvas, w, h, theme, c1, c2, t)
            drawBatteryRing(canvas, w, h, theme, t)
            drawNowPlaying(canvas, w, h, theme, t)
        }

        /**
         * Shows what's playing right now, if the user turned it on and music is playing.
         * Placed low on the screen, below the battery module, so it doesn't collide with
         * the system clock or notifications. Gently fades in and out so it never nags.
         */
        private fun drawNowPlaying(canvas: Canvas, w: Float, h: Float, theme: Profile, t: Float) {
            if (!ProfileManager.nowPlayingEnabled(this@GlowLiveWallpaper)) return

            // The media lookup isn't free, so only re-check about once a second.
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastTrackCheck > 1000L) {
                lastTrackCheck = nowMs
                lastTrack = MediaPlaybackDetector.nowPlaying(this@GlowLiveWallpaper)
            }
            val track = lastTrack ?: return

            val cx = w * 0.5f
            val cy = h * 0.80f
            val pulse = 0.80f + 0.20f * sin(t * 1.6f)

            paint.reset()
            paint.isAntiAlias = true
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            paint.textSize = w * 0.038f

            // Trim to fit the screen rather than spilling off the edges.
            val maxWidth = w * 0.78f
            var text = "\u266A  $track"
            if (paint.measureText(text) > maxWidth) {
                while (text.length > 4 && paint.measureText("$text\u2026") > maxWidth) {
                    text = text.dropLast(1)
                }
                text = "$text\u2026"
            }

            paint.color = withAlpha(theme.colorStart, (200 * pulse).toInt().coerceIn(0, 255))
            paint.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(theme.colorStart, 120))
            canvas.drawText(text, cx, cy, paint)
            paint.clearShadowLayer()
        }

        /** Deep navy field with a faint warm lift at the base. */
        private fun drawField(canvas: Canvas, w: Float, h: Float, warm: Int) {
            paint.reset()
            paint.isAntiAlias = true
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#05070F"),
                    Color.parseColor("#0A1128"),
                    Color.parseColor("#0C1430"),
                    blend(Color.parseColor("#0C1430"), warm, 0.20f)
                ),
                floatArrayOf(0f, 0.34f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        /**
         * Drifting, twinkling sparkles in the theme colours — the "unique pattern" that
         * makes the wallpaper feel alive. Each sparkle rises slowly, sways, and pulses
         * its brightness on its own rhythm, then wraps around at the top.
         */
        private fun drawSparkles(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int, t: Float, rainbow: Boolean) {
            if (!sparklesReady) return
            val minDim = if (w < h) w else h
            for (i in 0 until SPARKLE_COUNT) {
                // Rise and wrap
                var y = sy[i] - sSpeed[i] * t * 0.10f
                y -= kotlin.math.floor(y)                      // wrap to 0..1
                val sway = sin(t * 0.5f + sPhase[i]) * sDrift[i] * 0.04f
                var x = sx[i] + sway
                if (x < 0f) x += 1f
                if (x > 1f) x -= 1f

                // Twinkle: brightness pulses on its own phase
                val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2.1f + sPhase[i] * 1.7f))
                val alpha = (twinkle * 190f).toInt().coerceIn(0, 255)

                // In Spectrum mode every sparkle carries its own hue, drifting slowly —
                // so the field itself shows all colours, not just the theme pair.
                val color = if (rainbow) {
                    hsvTmp[0] = ((sPhase[i] * 57.3f) + t * 12f) % 360f
                    hsvTmp[1] = 0.9f; hsvTmp[2] = 1f
                    Color.HSVToColor(hsvTmp)
                } else if (i % 3 == 0) c2 else c1
                val r = sSize[i] * minDim * 0.0032f

                sparkPaint.color = withAlpha(color, alpha)
                sparkPaint.maskFilter = BlurMaskFilter(r * 2.6f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawCircle(x * w, y * h, r * 1.7f, sparkPaint)

                // Bright core
                sparkPaint.maskFilter = null
                sparkPaint.color = withAlpha(mix(color, Color.WHITE, 0.55f), alpha)
                canvas.drawCircle(x * w, y * h, r * 0.55f, sparkPaint)
            }
            sparkPaint.maskFilter = null
        }

        /**
         * The signature edge: a SLIM, smart line whose colours flow and morph continuously.
         *
         * Rather than a fixed two-colour sweep, we build a multi-stop gradient whose stops
         * are themselves drifting through the theme's hue range over time — so the colour
         * you see at any point on the edge melts into the next, and that into the next
         * again, in a never-repeating flowing phase. A slim bright core rides on a soft
         * halo, with a small comet head travelling the perimeter for life.
         */
        private fun drawFlowingEdge(canvas: Canvas, w: Float, h: Float, theme: Profile, c1: Int, c2: Int, t: Float) {
            // Build the flowing colour stops. Each stop shifts hue slightly over time, so
            // colours continuously transition into one another instead of just rotating.
            // Rainbow needs many stops across the FULL 360° wheel — with too few, whole
            // colour families never appear on the border at all.
            val stops = if (theme.rainbow) 13 else 7
            val colors = IntArray(stops)
            if (theme.rainbow) {
                for (i in 0 until stops) {
                    val hue = ((i / (stops - 1f)) * 360f + t * 26f) % 360f
                    hsvTmp[0] = hue; hsvTmp[1] = 1f; hsvTmp[2] = 1f
                    colors[i] = Color.HSVToColor(hsvTmp)
                }
            } else {
                // Morph between the theme's two colours, and let the blend point itself
                // travel — so the pair keeps re-mixing rather than sitting static.
                for (i in 0 until stops) {
                    val base = i / (stops - 1f)
                    val wave = 0.5f + 0.5f * sin(t * 0.9f + base * 6.283f)
                    colors[i] = mix(c1, c2, wave)
                }
            }
            // Close the loop so there is no seam where the gradient wraps.
            colors[stops - 1] = colors[0]

            // Slim = fine refined line. Aurora = the bold look: a thicker band whose bloom
            // spreads well into the screen, like coloured light washing in from the edges.
            val aurora = ProfileManager.wallpaperGlow(this@GlowLiveWallpaper) == 1
            val slim = if (aurora) w * 0.011f else w * 0.0042f
            // Sit flush against the true screen border — inset only by half the line's own
            // width so the stroke isn't clipped. Corner radius matches a modern phone's
            // rounded display, so the glow traces the actual edge of the screen.
            val inset = slim * 0.5f
            rect.set(inset, inset, w - inset, h - inset)
            val corner = (if (w < h) w else h) * 0.085f

            val sweep = SweepGradient(w / 2f, h / 2f, colors, null)
            edgeMatrix.setRotate(phase, w / 2f, h / 2f)
            sweep.setLocalMatrix(edgeMatrix)

            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.shader = sweep

            val breathe = 0.88f + 0.12f * sin(t * 1.1f)

            // 1) Wide, very soft halo. Drawn on a slightly INSET rect so the bloom spreads
            // inward across the screen instead of being clipped away at the border — the
            // core line still sits flush at the very edge. Aurora pushes this much wider
            // and brighter, so the colours visibly wash into the screen.
            val haloInset = if (aurora) w * 0.030f else w * 0.016f
            haloRect.set(haloInset, haloInset, w - haloInset, h - haloInset)
            val haloCorner = corner - (haloInset * 0.5f)
            paint.strokeWidth = slim * (if (aurora) 9.0f else 6.5f) * breathe
            paint.maskFilter = BlurMaskFilter(w * (if (aurora) 0.085f else 0.040f), BlurMaskFilter.Blur.NORMAL)
            paint.alpha = ((if (aurora) 170 else 120) * breathe).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(haloRect, haloCorner, haloCorner, paint)

            // 2) Tighter glow, hugging the core line at the edge.
            paint.strokeWidth = slim * 2.4f
            paint.maskFilter = BlurMaskFilter(slim * 2.6f, BlurMaskFilter.Blur.NORMAL)
            paint.alpha = if (aurora) 230 else 205
            canvas.drawRoundRect(rect, corner, corner, paint)

            // 3) The slim, crisp core line — flush against the true screen border.
            paint.maskFilter = null
            paint.strokeWidth = slim
            paint.alpha = 255
            canvas.drawRoundRect(rect, corner, corner, paint)

            paint.shader = null
            paint.maskFilter = null

            // 4) A small comet head travelling the perimeter — gives the slim line a sense
            // of direction and life, picking up whatever colour is flowing at that point.
            drawCometHead(canvas, rect, corner, colors, slim, t)
        }

        /** A bright bead that runs along the rounded-rect edge path, trailing a soft glow. */
        private fun drawCometHead(
            canvas: Canvas, r: RectF, corner: Float, colors: IntArray, slim: Float, t: Float
        ) {
            edgePath.reset()
            edgePath.addRoundRect(r, corner, corner, android.graphics.Path.Direction.CW)
            pathMeasure.setPath(edgePath, false)
            val len = pathMeasure.length
            if (len <= 0f) return

            val headColor = colors[((t * 1.4f).toInt()) % colors.size]

            // Head plus a few trailing beads that fade behind it.
            for (k in 0 until 6) {
                val d = ((t * 0.16f - k * 0.012f) % 1f + 1f) % 1f * len
                if (!pathMeasure.getPosTan(d, posTmp, null)) continue
                val fade = 1f - k / 6f
                val rad = slim * (1.9f - k * 0.22f).coerceAtLeast(0.4f)

                paint.style = Paint.Style.FILL
                paint.color = withAlpha(headColor, (220 * fade * fade).toInt())
                paint.maskFilter = BlurMaskFilter(slim * 3.2f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawCircle(posTmp[0], posTmp[1], rad * 1.6f, paint)

                if (k == 0) {
                    paint.maskFilter = null
                    paint.color = withAlpha(mix(headColor, Color.WHITE, 0.8f), 245)
                    canvas.drawCircle(posTmp[0], posTmp[1], rad * 0.55f, paint)
                }
            }
            paint.maskFilter = null
        }

        /** Battery module — delegated to the shared renderer so the static wallpaper
         *  shows exactly the same design. Style is chosen in Settings. */
        private fun drawBatteryRing(canvas: Canvas, w: Float, h: Float, theme: Profile, t: Float) {
            val (level, charging, tempC) = batteryInfo()
            val style = ProfileManager.batteryStyle(this@GlowLiveWallpaper)
            BatteryModule.draw(canvas, w, h, theme, level, charging, style, t, tempC)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        private fun blend(a: Int, b: Int, tt: Float): Int {
            val f = tt.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
            )
        }
        private fun mix(a: Int, b: Int, tt: Float): Int = blend(a, b, tt)
    }
}
