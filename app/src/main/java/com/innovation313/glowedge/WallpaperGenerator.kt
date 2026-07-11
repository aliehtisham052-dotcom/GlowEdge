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
        drawSparkles(canvas, fw, fh, theme)
        drawEdgeShine(canvas, fw, fh, theme)
        return bmp
    }

    /**
     * A still field of sparkles in the theme colours — the same visual language the live
     * wallpaper animates, captured as one composed moment. Uses a fixed seed so the
     * layout is deliberate and repeatable, not random noise each time.
     */
    private fun drawSparkles(canvas: Canvas, w: Float, h: Float, theme: Profile) {
        val rnd = java.util.Random(313L)
        val minDim = if (w < h) w else h
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val count = 54
        for (i in 0 until count) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val size = (0.5f + rnd.nextFloat() * 1.7f) * minDim * 0.0032f
            val bright = 0.35f + rnd.nextFloat() * 0.65f
            val alpha = (bright * 190f).toInt().coerceIn(0, 255)
            val color = if (i % 3 == 0) theme.colorEnd else theme.colorStart

            paint.color = withAlpha(color, alpha)
            paint.maskFilter = BlurMaskFilter(size * 2.6f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(x, y, size * 1.7f, paint)

            paint.maskFilter = null
            paint.color = withAlpha(mix(color, Color.WHITE, 0.55f), alpha)
            canvas.drawCircle(x, y, size * 0.55f, paint)
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
