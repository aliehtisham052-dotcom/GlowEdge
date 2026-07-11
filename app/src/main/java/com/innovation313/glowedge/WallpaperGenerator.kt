package com.innovation313.glowedge

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates GlowEdge-branded wallpapers on-device (no bundled image assets needed).
 * Each wallpaper echoes the app's own glow visual language, in a given theme's colors,
 * with a choice of layout templates and a theme-name + branding label.
 */
object WallpaperGenerator {

    /** Three distinct layout templates, for real variety rather than one repeated look. */
    const val TEMPLATE_BORDER = 0
    const val TEMPLATE_RADIAL = 1
    const val TEMPLATE_DIAGONAL = 2
    const val TEMPLATE_COUNT = 3

    val templateNames = listOf("Border Glow", "Radial Burst", "Diagonal Wave")

    private val RAINBOW = intArrayOf(
        Color.parseColor("#FF3B5C"), Color.parseColor("#FFD93B"), Color.parseColor("#3BE885"),
        Color.parseColor("#3BD4FF"), Color.parseColor("#B93BFF"), Color.parseColor("#FF3B5C")
    )

    fun generate(theme: Profile, width: Int, height: Int, template: Int = TEMPLATE_BORDER): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        drawBase(canvas, w, h)

        when (template) {
            TEMPLATE_RADIAL -> drawRadial(canvas, theme, w, h)
            TEMPLATE_DIAGONAL -> drawDiagonal(canvas, theme, w, h)
            else -> drawBorder(canvas, theme, w, h)
        }

        drawLabel(canvas, theme, w, h)
        return bmp
    }

    /** Base: deep navy vertical gradient, matching the app's own background. */
    private fun drawBase(canvas: Canvas, w: Int, h: Int) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0A1128"), Color.parseColor("#0E1631"), Color.parseColor("#0A1128")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
    }

    /** Template 1: glowing rounded-rect edge border, the app's own signature look. */
    private fun drawBorder(canvas: Canvas, theme: Profile, w: Int, h: Int) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        haloPaint.shader = RadialGradient(
            w * 0.18f, h * 0.14f, w * 0.7f,
            intArrayOf(withAlpha(theme.colorStart, 85), withAlpha(theme.colorStart, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), haloPaint)
        haloPaint.shader = RadialGradient(
            w * 0.82f, h * 0.86f, w * 0.7f,
            intArrayOf(withAlpha(theme.colorEnd, 85), withAlpha(theme.colorEnd, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), haloPaint)

        val thickness = w * 0.02f
        val inset = thickness * 1.4f
        val rect = RectF(inset, inset, w - inset, h - inset)
        val corner = w * 0.10f
        val colors = if (theme.rainbow) RAINBOW else intArrayOf(theme.colorStart, theme.colorEnd, theme.colorStart)

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        glowPaint.style = Paint.Style.STROKE
        glowPaint.strokeCap = Paint.Cap.ROUND
        glowPaint.shader = SweepGradient(w / 2f, h / 2f, colors, null)
        glowPaint.strokeWidth = thickness
        glowPaint.maskFilter = BlurMaskFilter(thickness * 1.6f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(rect, corner, corner, glowPaint)

        glowPaint.maskFilter = null
        glowPaint.strokeWidth = thickness * 0.35f
        canvas.drawRoundRect(rect, corner, corner, glowPaint)
    }

    /** Template 2: a bright radial burst from center with soft radiating light rays. */
    private fun drawRadial(canvas: Canvas, theme: Profile, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h * 0.42f

        val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        rayPaint.style = Paint.Style.STROKE
        rayPaint.strokeWidth = w * 0.008f
        rayPaint.maskFilter = BlurMaskFilter(w * 0.012f, BlurMaskFilter.Blur.NORMAL)
        val rayCount = 12
        val len = w * 0.62f
        for (i in 0 until rayCount) {
            val angle = (i / rayCount.toDouble()) * 2 * Math.PI
            val ex = cx + (cos(angle) * len).toFloat()
            val ey = cy + (sin(angle) * len).toFloat()
            rayPaint.color = withAlpha(if (i % 2 == 0) theme.colorStart else theme.colorEnd, 65)
            canvas.drawLine(cx, cy, ex, ey, rayPaint)
        }

        val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        burstPaint.shader = RadialGradient(
            cx, cy, w * 0.62f,
            intArrayOf(withAlpha(theme.colorStart, 220), withAlpha(theme.colorEnd, 100), withAlpha(theme.colorEnd, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), burstPaint)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        corePaint.color = Color.WHITE
        corePaint.alpha = 235
        corePaint.maskFilter = BlurMaskFilter(w * 0.05f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, w * 0.05f, corePaint)
    }

    /** Template 3: two soft diagonal light bands sweeping across the screen. */
    private fun drawDiagonal(canvas: Canvas, theme: Profile, w: Int, h: Int) {
        canvas.save()
        canvas.rotate(-16f, w / 2f, h / 2f)
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bandPaint.maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL)
        val bandH = h * 0.16f

        bandPaint.color = withAlpha(theme.colorStart, 150)
        canvas.drawRect(-w * 0.3f, h * 0.16f, w * 1.3f, h * 0.16f + bandH, bandPaint)

        bandPaint.color = withAlpha(if (theme.rainbow) RAINBOW[2] else theme.colorEnd, 130)
        canvas.drawRect(-w * 0.3f, h * 0.46f, w * 1.3f, h * 0.46f + bandH, bandPaint)

        bandPaint.color = withAlpha(theme.colorEnd, 150)
        canvas.drawRect(-w * 0.3f, h * 0.76f, w * 1.3f, h * 0.76f + bandH, bandPaint)
        canvas.restore()
    }

    /** Theme name + small GlowEdge branding, near the bottom, on every template. */
    private fun drawLabel(canvas: Canvas, theme: Profile, w: Int, h: Int) {
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        namePaint.textAlign = Paint.Align.CENTER
        namePaint.typeface = Typeface.DEFAULT_BOLD
        namePaint.textSize = w * 0.05f
        namePaint.shader = LinearGradient(
            w / 2f - w * 0.3f, 0f, w / 2f + w * 0.3f, 0f,
            theme.colorStart, theme.colorEnd, Shader.TileMode.CLAMP
        )
        namePaint.maskFilter = BlurMaskFilter(w * 0.004f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawText(theme.name, w / 2f, h * 0.935f, namePaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        brandPaint.textAlign = Paint.Align.CENTER
        brandPaint.color = Color.parseColor("#8A93B5")
        brandPaint.textSize = w * 0.022f
        brandPaint.letterSpacing = 0.12f
        canvas.drawText("GLOWEDGE", w / 2f, h * 0.965f, brandPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
