package com.innovation313.glowedge

import android.content.Context
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

/**
 * Generates GlowEdge-branded wallpapers on-device (no bundled image assets needed).
 * Each wallpaper echoes the app's own glow-border visual language, in a given theme's
 * colors, so it feels like the phone is "always glowing" even as a static wallpaper.
 */
object WallpaperGenerator {

    private val RAINBOW = intArrayOf(
        Color.parseColor("#FF3B5C"), Color.parseColor("#FFD93B"), Color.parseColor("#3BE885"),
        Color.parseColor("#3BD4FF"), Color.parseColor("#B93BFF"), Color.parseColor("#FF3B5C")
    )

    fun generate(theme: Profile, width: Int, height: Int): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Base: deep navy vertical gradient, matching the app's own background.
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#0A1128"), Color.parseColor("#0E1631"), Color.parseColor("#0A1128")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Soft corner halos in the theme's own colors, for depth.
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

        // Glowing rounded-rect edge border, exactly the app's own signature look.
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

        // Sharper inner line on top for definition.
        glowPaint.maskFilter = null
        glowPaint.strokeWidth = thickness * 0.35f
        canvas.drawRoundRect(rect, corner, corner, glowPaint)

        return bmp
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
