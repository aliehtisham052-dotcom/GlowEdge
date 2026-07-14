package com.innovation313.glowedge

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the battery module for the wallpapers. Shared by the live wallpaper and the
 * static wallpaper so both always render the same design in the same place — the static
 * one simply passes a fixed time, so it looks like a frozen frame of the live one.
 *
 * Three distinct styles, chosen in Settings:
 *   ORBIT    — a ring with tick marks and a bright bead orbiting the filled arc
 *   SEGMENTS — a dial split into discrete lit segments, like a premium fuel gauge
 *   CAPSULE  — a minimal horizontal capsule that fills, with a soft inner shine
 */
object BatteryModule {

    const val STYLE_ORBIT = 0
    const val STYLE_SEGMENTS = 1
    const val STYLE_COUNT = 2

    val styleNames = listOf("Orbit", "Segments")

    /**
     * @param t seconds since start — drives the animated parts. Pass a fixed value (e.g. 0f)
     *          for a still render.
     */
    fun draw(
        canvas: Canvas, w: Float, h: Float, theme: Profile,
        level: Int, charging: Boolean, style: Int, t: Float, tempC: Float = -1f
    ) {
        val color = when {
            charging -> theme.colorEnd
            level <= 15 -> Color.parseColor("#FF5252")
            else -> theme.colorStart
        }
        when (style) {
            STYLE_SEGMENTS -> drawSegments(canvas, w, h, theme, level, charging, color, t)
            else -> drawOrbit(canvas, w, h, theme, level, charging, color, t)
        }
        drawTemperature(canvas, w, h, tempC)
    }


    /**
     * Battery temperature, in a small line under the module. This is real battery info the
     * phone does not surface anywhere on the lock screen — unlike signal or WiFi, which the
     * status bar already shows, so it adds something instead of duplicating it.
     *
     * Turns amber above 40C and red above 45C, which is when heat actually starts hurting
     * the cell. Skipped entirely if the device didn't report a temperature.
     *
     * NOTE: charging wattage was deliberately NOT kept here. Phones disagree on the unit
     * and sign of the current reading, and some report a configured value rather than a
     * real measurement — so the figure could not be trusted, and a wrong number is worse
     * than no number.
     */
    private fun drawTemperature(canvas: Canvas, w: Float, h: Float, tempC: Float) {
        if (tempC <= 0f) return
        val cy = h * 0.60f
        val y = cy + w * 0.125f

        val color = when {
            tempC >= 45f -> Color.parseColor("#FF5252")
            tempC >= 40f -> Color.parseColor("#FFB300")
            else -> withAlpha(Color.WHITE, 150)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = w * 0.032f
        paint.letterSpacing = 0.10f
        paint.color = color
        canvas.drawText(String.format("%.0f\u00B0C", tempC), w * 0.5f, y, paint)
    }

    // ---------------------------------------------------------------- ORBIT

    /** A ring with fine tick marks, a glowing progress arc and a bead orbiting the arc. */
    private fun drawOrbit(
        canvas: Canvas, w: Float, h: Float, theme: Profile,
        level: Int, charging: Boolean, color: Int, t: Float
    ) {
        val cx = w * 0.5f
        val cy = h * 0.60f
        val r = w * 0.155f
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val stroke = w * 0.020f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // Tick marks
        paint.strokeWidth = w * 0.004f
        paint.color = withAlpha(Color.WHITE, 40)
        val tickR1 = r + stroke * 1.1f
        val tickR2 = tickR1 + w * 0.014f
        for (i in 0 until 40) {
            val a = (i / 40f) * 2f * Math.PI
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            canvas.drawLine(cx + ca * tickR1, cy + sa * tickR1, cx + ca * tickR2, cy + sa * tickR2, paint)
        }

        // Track
        paint.strokeWidth = stroke
        paint.color = withAlpha(Color.WHITE, 32)
        canvas.drawArc(rect, 0f, 360f, false, paint)

        // Glowing progress arc
        val sweepDeg = 360f * (level / 100f)
        paint.color = color
        paint.maskFilter = BlurMaskFilter(w * 0.022f, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = 180
        canvas.drawArc(rect, -90f, sweepDeg, false, paint)
        paint.maskFilter = null
        paint.alpha = 255
        canvas.drawArc(rect, -90f, sweepDeg, false, paint)

        // Orbiting bead along the filled arc
        if (level > 2) {
            val headDeg = -90f + (t * 40f) % sweepDeg.coerceAtLeast(1f)
            val a = Math.toRadians(headDeg.toDouble())
            val hx = cx + (cos(a) * r).toFloat()
            val hy = cy + (sin(a) * r).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(mix(color, Color.WHITE, 0.7f), 230)
            paint.maskFilter = BlurMaskFilter(w * 0.018f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(hx, hy, stroke * 0.62f, paint)
            paint.maskFilter = null
            paint.color = Color.WHITE
            paint.alpha = 220
            canvas.drawCircle(hx, hy, stroke * 0.26f, paint)
        }

        drawCentreText(canvas, cx, cy, w, level, charging, color, theme, t)
    }

    // ------------------------------------------------------------- SEGMENTS

    /**
     * A dial split into discrete lit segments, like a premium gauge: each segment that
     * falls under the current level lights up, the rest stay as dark placeholders. The
     * leading segment pulses gently so the dial feels alive.
     */
    private fun drawSegments(
        canvas: Canvas, w: Float, h: Float, theme: Profile,
        level: Int, charging: Boolean, color: Int, t: Float
    ) {
        val cx = w * 0.5f
        val cy = h * 0.60f
        val rOuter = w * 0.175f
        val rInner = w * 0.130f

        val total = 30
        val lit = ((level / 100f) * total).toInt().coerceIn(0, total)
        // Leave a gap at the bottom so the dial reads as a gauge, not a full circle.
        val startDeg = 130f
        val spanDeg = 280f
        val perDeg = spanDeg / total
        val gapDeg = perDeg * 0.28f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = rOuter - rInner
        val midR = (rOuter + rInner) / 2f
        val rect = RectF(cx - midR, cy - midR, cx + midR, cy + midR)

        for (i in 0 until total) {
            val a0 = startDeg + i * perDeg + gapDeg / 2f
            val sweep = perDeg - gapDeg
            if (i < lit) {
                // Leading segment breathes; the rest are steady.
                val isLead = (i == lit - 1)
                val pulse = if (isLead) 0.65f + 0.35f * (0.5f + 0.5f * sin(t * 3.2f)) else 1f

                paint.color = color
                paint.maskFilter = BlurMaskFilter(w * 0.016f, BlurMaskFilter.Blur.NORMAL)
                paint.alpha = (150 * pulse).toInt().coerceIn(0, 255)
                canvas.drawArc(rect, a0, sweep, false, paint)

                paint.maskFilter = null
                paint.alpha = (255 * pulse).toInt().coerceIn(0, 255)
                canvas.drawArc(rect, a0, sweep, false, paint)
            } else {
                paint.maskFilter = null
                paint.color = withAlpha(Color.WHITE, 26)
                paint.alpha = 26
                canvas.drawArc(rect, a0, sweep, false, paint)
            }
        }
        paint.maskFilter = null
        drawCentreText(canvas, cx, cy, w, level, charging, color, theme, t)
    }

    // --------------------------------------------------------------- shared

    /** Big number + status word, used by the two dial styles. */
    private fun drawCentreText(
        canvas: Canvas, cx: Float, cy: Float, w: Float,
        level: Int, charging: Boolean, color: Int, theme: Profile, t: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        paint.textSize = w * 0.105f
        paint.color = Color.WHITE
        paint.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(color, 150))
        canvas.drawText("$level%", cx, cy + w * 0.018f, paint)
        paint.clearShadowLayer()

        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = w * 0.030f
        paint.letterSpacing = 0.22f
        val label = if (charging) "CHARGING" else if (level <= 15) "LOW" else "BATTERY"
        paint.color = withAlpha(color, 210)
        canvas.drawText(label, cx, cy + w * 0.075f, paint)
        paint.letterSpacing = 0f

        if (charging) {
            val pulse = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * 3.0f))
            paint.textSize = w * 0.055f
            paint.color = withAlpha(theme.colorEnd, (255 * pulse).toInt().coerceIn(0, 255))
            canvas.drawText("\u26A1", cx, cy - w * 0.058f, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )
    }
}
