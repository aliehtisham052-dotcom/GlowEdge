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
        level: Int, charging: Boolean, style: Int, t: Float,
        tempC: Float = -1f, watts: Float = -1f
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
        drawTemperature(canvas, w, h, tempC, if (charging) watts else -1f)
    }

    /**
     * Turns the phone's raw current + voltage readings into charging watts.
     *
     * This needs care, because phones genuinely disagree here:
     *   - The Android spec says CURRENT_NOW is in MICROamps, but several manufacturers
     *     (Samsung among them) report MILLIamps instead — so the unit must be inferred
     *     from the magnitude rather than trusted.
     *   - The sign convention varies too (positive vs negative while charging), so we
     *     take the absolute value.
     *   - Some charger drivers report a *configured* value rather than a real measurement,
     *     which is meaningless.
     *
     * Because of that, the result is sanity-checked: if it isn't a plausible charging
     * figure we return -1 and simply show nothing. Showing no number is honest; showing
     * a wrong one is not.
     *
     * @param currentRaw BatteryManager.BATTERY_PROPERTY_CURRENT_NOW as reported
     * @param voltageMilliVolts BatteryManager.EXTRA_VOLTAGE as reported
     * @return watts, or -1f if the device's numbers can't be trusted
     */
    fun computeWatts(currentRaw: Int, voltageMilliVolts: Int): Float {
        if (currentRaw == 0 || voltageMilliVolts <= 0) return -1f
        val magnitude = kotlin.math.abs(currentRaw.toLong())

        // Infer the unit from the magnitude. A real charging current is roughly
        // 0.1A–10A, which is 100,000–10,000,000 µA or 100–10,000 mA.
        val amps: Float = when {
            magnitude > 50_000L -> magnitude / 1_000_000f    // reported in microamps
            magnitude > 50L -> magnitude / 1_000f            // reported in milliamps
            else -> return -1f                               // implausibly small
        }

        // Voltage is normally millivolts (~3,700–4,500), but be tolerant of volts.
        val volts: Float = if (voltageMilliVolts > 100) voltageMilliVolts / 1000f
                           else voltageMilliVolts.toFloat()
        if (volts < 2f || volts > 30f) return -1f            // not a believable pack voltage

        val watts = amps * volts
        // Anything outside this range is the device reporting nonsense, not a real charger.
        return if (watts in 0.4f..250f) watts else -1f
    }

    /**
     * Battery temperature — and, while charging, the charging wattage — in a small line
     * under the module. This is real battery info the phone does not surface anywhere on
     * the lock screen, unlike signal or WiFi which the status bar already shows.
     *
     * Temperature turns amber above 40C and red above 45C, which is when heat actually
     * starts hurting the cell. Either value is skipped if the device didn't report it.
     */
    private fun drawTemperature(canvas: Canvas, w: Float, h: Float, tempC: Float, watts: Float) {
        val hasTemp = tempC > 0f
        val hasWatts = watts > 0f
        if (!hasTemp && !hasWatts) return

        val cy = h * 0.60f
        val y = cy + w * 0.125f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = w * 0.032f
        paint.letterSpacing = 0.10f

        // Watts alone, temperature alone, or both separated by a thin divider.
        val wattText = if (hasWatts) {
            if (watts >= 10f) String.format("%.0fW", watts) else String.format("%.1fW", watts)
        } else null
        val tempText = if (hasTemp) String.format("%.0f\u00B0C", tempC) else null

        if (wattText != null && tempText != null) {
            // Draw them side by side so the charging figure gets its own colour.
            val gap = w * 0.018f
            val wWatt = paint.measureText(wattText)
            val wTemp = paint.measureText(tempText)
            val divider = "\u00B7"
            val wDiv = paint.measureText(divider)
            val total = wWatt + gap + wDiv + gap + wTemp
            var x = w * 0.5f - total / 2f

            paint.textAlign = Paint.Align.LEFT
            paint.color = withAlpha(Color.parseColor("#69F0AE"), 210)   // charging = green
            canvas.drawText(wattText, x, y, paint)
            x += wWatt + gap

            paint.color = withAlpha(Color.WHITE, 90)
            canvas.drawText(divider, x, y, paint)
            x += wDiv + gap

            paint.color = tempColor(tempC)
            canvas.drawText(tempText, x, y, paint)
        } else if (wattText != null) {
            paint.color = withAlpha(Color.parseColor("#69F0AE"), 210)
            canvas.drawText(wattText, w * 0.5f, y, paint)
        } else if (tempText != null) {
            paint.color = tempColor(tempC)
            canvas.drawText(tempText, w * 0.5f, y, paint)
        }
    }

    private fun tempColor(tempC: Float): Int = when {
        tempC >= 45f -> Color.parseColor("#FF5252")
        tempC >= 40f -> Color.parseColor("#FFB300")
        else -> withAlpha(Color.WHITE, 150)
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
