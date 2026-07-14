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
        Color.parseColor("#FF3B5C"), Color.parseColor("#FF8A3B"),
        Color.parseColor("#FFD93B"), Color.parseColor("#3BE885"),
        Color.parseColor("#3BD4FF"), Color.parseColor("#3B7BFF"),
        Color.parseColor("#B93BFF"), Color.parseColor("#FF3B5C")
    )

    fun generate(theme: Profile, width: Int, height: Int, template: Int = TEMPLATE_BORDER, aurora: Boolean = false): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()

        drawField(canvas, fw, fh)
        drawSparkles(canvas, fw, fh, theme)
        drawEdgeShine(canvas, fw, fh, theme, aurora)
        return bmp
    }

    /**
     * Same as generate(), but also renders the battery module — so the static wallpaper
     * carries the same battery design as the live one. Takes the level explicitly, since
     * a static image is a snapshot: it shows the charge at the moment it was applied.
     */
    fun generateWithBattery(
        theme: Profile, width: Int, height: Int,
        level: Int, charging: Boolean, batteryStyle: Int, aurora: Boolean = false,
        tempC: Float = -1f
    ): Bitmap {
        val bmp = generate(theme, width, height, aurora = aurora)
        val canvas = Canvas(bmp)
        BatteryModule.draw(
            canvas, bmp.width.toFloat(), bmp.height.toFloat(),
            theme, level, charging, batteryStyle, 0f, tempC   // t=0: a still frame
        )
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
            val color = if (theme.rainbow) {
                RAINBOW[i % (RAINBOW.size - 1)]
            } else if (i % 3 == 0) theme.colorEnd else theme.colorStart

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
    private fun drawEdgeShine(canvas: Canvas, w: Float, h: Float, theme: Profile, aurora: Boolean = false) {
        // Multi-stop gradient so the colour reads as flowing from one into the next,
        // matching the live wallpaper's language even in this still image.
        val colors = if (theme.rainbow) RAINBOW
                     else intArrayOf(
                         theme.colorStart,
                         mix(theme.colorStart, theme.colorEnd, 0.5f),
                         theme.colorEnd,
                         mix(theme.colorEnd, theme.colorStart, 0.5f),
                         theme.colorStart
                     )

        val slim = if (aurora) w * 0.011f else w * 0.0042f   // Aurora = bold flowing bloom
        // Flush against the true screen border, inset only by half the stroke width so the
        // line isn't clipped. Corner radius traces a modern phone's rounded display.
        val inset = slim * 0.5f
        val rect = RectF(inset, inset, w - inset, h - inset)
        val corner = (if (w < h) w else h) * 0.085f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.shader = android.graphics.SweepGradient(w / 2f, h / 2f, colors, null)

        // Wide soft halo, drawn on a slightly inset rect so the bloom spreads inward across
        // the screen rather than being clipped at the border.
        val haloInset = if (aurora) w * 0.030f else w * 0.016f
        val haloRect = RectF(haloInset, haloInset, w - haloInset, h - haloInset)
        val haloCorner = corner - (haloInset * 0.5f)
        paint.strokeWidth = slim * (if (aurora) 9.0f else 6.5f)
        paint.maskFilter = BlurMaskFilter(w * (if (aurora) 0.085f else 0.040f), BlurMaskFilter.Blur.NORMAL)
        paint.alpha = if (aurora) 170 else 120
        canvas.drawRoundRect(haloRect, haloCorner, haloCorner, paint)

        // Tighter glow hugging the core line at the edge.
        paint.strokeWidth = slim * 2.4f
        paint.maskFilter = BlurMaskFilter(slim * 2.6f, BlurMaskFilter.Blur.NORMAL)
        paint.alpha = if (aurora) 230 else 205
        canvas.drawRoundRect(rect, corner, corner, paint)

        // The slim, crisp core line — flush against the true screen border.
        paint.maskFilter = null
        paint.strokeWidth = slim
        paint.alpha = 255
        canvas.drawRoundRect(rect, corner, corner, paint)
    }
}
