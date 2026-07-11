package com.innovation313.glowedge

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Generates a clean GlowEdge wallpaper on-device (no bundled image assets).
 *
 * Deliberately minimal: a deep navy field with a soft glowing shine along the screen
 * EDGES in the theme colours — nothing in the middle, no text, no theme name, no
 * branding. The wallpaper stays a quiet backdrop; any live info (time/date/battery)
 * is drawn on top by the live wallpaper, not baked into the image.
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

        drawField(canvas, fw, fh)
        drawEdgeShine(canvas, fw, fh, theme)
        return bmp
    }

    /** Deep navy vertical field, matching the app's own background. */
    private fun drawField(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#070B18"),
                Color.parseColor("#0A1128"),
                Color.parseColor("#080D1F")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    /**
     * A soft shine glowing along the screen edges: a wide blurred rounded-rect stroke
     * in the theme's gradient, then a crisp thin inner line for definition. This is the
     * whole design — clean, edge-only, nothing in the centre.
     */
    private fun drawEdgeShine(canvas: Canvas, w: Float, h: Float, theme: Profile) {
        val colors = if (theme.rainbow) RAINBOW
                     else intArrayOf(theme.colorStart, theme.colorEnd, theme.colorStart, theme.colorEnd, theme.colorStart)

        val thickness = w * 0.018f
        val inset = thickness * 1.6f
        val rect = RectF(inset, inset, w - inset, h - inset)
        val corner = w * 0.11f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // Wide, very soft outer bloom.
        paint.shader = android.graphics.SweepGradient(w / 2f, h / 2f, colors, null)
        paint.strokeWidth = thickness * 2.4f
        paint.maskFilter = BlurMaskFilter(w * 0.06f, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = 150
        canvas.drawRoundRect(rect, corner, corner, paint)

        // Mid glow.
        paint.strokeWidth = thickness
        paint.maskFilter = BlurMaskFilter(thickness * 1.4f, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = 235
        canvas.drawRoundRect(rect, corner, corner, paint)

        // Crisp thin inner line.
        paint.maskFilter = null
        paint.strokeWidth = thickness * 0.28f
        paint.alpha = 255
        canvas.drawRoundRect(rect, corner, corner, paint)
    }
}
