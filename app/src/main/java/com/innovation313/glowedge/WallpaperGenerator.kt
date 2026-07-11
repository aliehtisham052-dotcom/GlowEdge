package com.innovation313.glowedge

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.min

/**
 * Generates a single, carefully-designed GlowEdge lock-screen wallpaper on-device.
 *
 * Design intent (this is a lock screen, so the top must stay clear for the system
 * clock and notifications, and the visual weight belongs in the lower half):
 *   - A deep vertical navy field that darkens toward the top so white system text
 *     stays readable, and warms slightly with the theme colour toward the bottom.
 *   - The signature: a soft "column of light" rising from the lower third — a
 *     tapered, blurred beam in the theme's own gradient, like the glow gathering
 *     into a still shape. This is the one bold element; everything else stays quiet.
 *   - A single hairline arc catching the light at the base of the beam, for craft.
 *   - A refined thin edge frame (not a thick neon border) so it reads premium.
 *   - Theme name in the theme gradient + small GLOWEDGE wordmark, bottom-anchored.
 *
 * No bundled image assets; everything is drawn, so it scales to any screen size.
 */
object WallpaperGenerator {

    // Kept for API compatibility with callers that still pass a template argument.
    const val TEMPLATE_BORDER = 0
    const val TEMPLATE_COUNT = 1
    val templateNames = listOf("Signature")

    private val RAINBOW = intArrayOf(
        Color.parseColor("#FF3B5C"), Color.parseColor("#FFD93B"), Color.parseColor("#3BE885"),
        Color.parseColor("#3BD4FF"), Color.parseColor("#B93BFF"), Color.parseColor("#FF3B5C")
    )

    fun generate(theme: Profile, width: Int, height: Int, template: Int = TEMPLATE_BORDER): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()

        val c1 = theme.colorStart
        val c2 = theme.colorEnd
        val beamColor = if (theme.rainbow) RAINBOW[3] else c1

        drawField(canvas, fw, fh, c2)
        drawLightColumn(canvas, fw, fh, c1, c2, beamColor)
        drawBaseArc(canvas, fw, fh, beamColor)
        drawFrame(canvas, fw, fh, c1, c2)
        drawLabel(canvas, theme, fw, fh)

        return bmp
    }

    /**
     * Base field: near-black navy at the very top (clock area stays legible),
     * deepening through the app's navy, then lifting with a whisper of the theme
     * colour in the bottom eighth so the screen feels lit from below.
     */
    private fun drawField(canvas: Canvas, w: Float, h: Float, warm: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#05070F"),           // top: almost black, for status/clock legibility
                Color.parseColor("#0A1128"),           // upper-mid: app navy
                Color.parseColor("#0C1430"),           // lower-mid
                blend(Color.parseColor("#0C1430"), warm, 0.22f) // base: faint warm lift
            ),
            floatArrayOf(0f, 0.34f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /**
     * The signature: a tapered vertical beam of light rising from the lower third.
     * Built from a soft wide blurred core plus a brighter narrow inner core, both
     * in the theme gradient, fading to nothing before it reaches the top.
     */
    private fun drawLightColumn(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int, beam: Int) {
        val cx = w / 2f
        val topY = h * 0.30f      // beam fades out here (leaves clock space above)
        val baseY = h * 0.86f     // beam sits on the base arc here
        val halfWideBase = w * 0.20f
        val halfWideTop = w * 0.015f

        // Wide, very soft outer glow — a trapezoid tapering upward.
        val outer = Path().apply {
            moveTo(cx - halfWideBase, baseY)
            lineTo(cx - halfWideTop, topY)
            lineTo(cx + halfWideTop, topY)
            lineTo(cx + halfWideBase, baseY)
            close()
        }
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        outerPaint.shader = LinearGradient(
            0f, baseY, 0f, topY,
            intArrayOf(withAlpha(c1, 150), withAlpha(c2, 60), withAlpha(c2, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        outerPaint.maskFilter = BlurMaskFilter(w * 0.10f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(outer, outerPaint)

        // Brighter, narrow inner core.
        val innerHalfBase = w * 0.055f
        val inner = Path().apply {
            moveTo(cx - innerHalfBase, baseY)
            lineTo(cx - halfWideTop * 0.6f, topY + (baseY - topY) * 0.10f)
            lineTo(cx + halfWideTop * 0.6f, topY + (baseY - topY) * 0.10f)
            lineTo(cx + innerHalfBase, baseY)
            close()
        }
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        innerPaint.shader = LinearGradient(
            0f, baseY, 0f, topY,
            intArrayOf(withAlpha(mixToward(beam, Color.WHITE, 0.35f), 220), withAlpha(beam, 40), withAlpha(beam, 0)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        innerPaint.maskFilter = BlurMaskFilter(w * 0.03f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(inner, innerPaint)
    }

    /** A single hairline arc catching light where the beam meets the base — a crafted detail. */
    private fun drawBaseArc(canvas: Canvas, w: Float, h: Float, beam: Int) {
        val cx = w / 2f
        val cy = h * 0.86f
        val r = w * 0.30f
        val arc = RectF(cx - r, cy - r * 0.5f, cx + r, cy + r * 0.5f)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.color = withAlpha(beam, 160)
        glowPaint.strokeWidth = w * 0.006f
        glowPaint.maskFilter = BlurMaskFilter(w * 0.02f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawArc(arc, 200f, 140f, false, glowPaint)

        glowPaint.maskFilter = null
        glowPaint.color = withAlpha(mixToward(beam, Color.WHITE, 0.4f), 200)
        glowPaint.strokeWidth = w * 0.0022f
        canvas.drawArc(arc, 205f, 130f, false, glowPaint)
    }

    /** Refined thin edge frame — a whisper of the theme gradient, not a neon border. */
    private fun drawFrame(canvas: Canvas, w: Float, h: Float, c1: Int, c2: Int) {
        val inset = w * 0.045f
        val rect = RectF(inset, inset, w - inset, h - inset)
        val corner = w * 0.085f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.003f
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(withAlpha(c1, 70), withAlpha(c2, 130), withAlpha(c1, 70)),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    /** Theme name in the theme gradient + small tracked GLOWEDGE wordmark, bottom-anchored. */
    private fun drawLabel(canvas: Canvas, theme: Profile, w: Float, h: Float) {
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        namePaint.textAlign = Paint.Align.CENTER
        namePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        namePaint.textSize = w * 0.058f
        namePaint.letterSpacing = 0.01f
        val half = min(w * 0.34f, namePaint.measureText(theme.name) * 0.6f + w * 0.08f)
        namePaint.shader = LinearGradient(
            w / 2f - half, 0f, w / 2f + half, 0f,
            theme.colorStart, theme.colorEnd, Shader.TileMode.CLAMP
        )
        namePaint.setShadowLayer(w * 0.02f, 0f, 0f, withAlpha(theme.colorStart, 120))
        canvas.drawText(theme.name, w / 2f, h * 0.925f, namePaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        brandPaint.textAlign = Paint.Align.CENTER
        brandPaint.color = Color.parseColor("#7C86A8")
        brandPaint.textSize = w * 0.023f
        brandPaint.letterSpacing = 0.34f
        brandPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("GLOWEDGE", w / 2f, h * 0.955f, brandPaint)
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

    private fun mixToward(color: Int, target: Int, t: Float): Int = blend(color, target, t)
}
