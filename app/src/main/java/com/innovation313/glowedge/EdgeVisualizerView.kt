package com.innovation313.glowedge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.max

class EdgeVisualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var styleId = GlowStyles.GLOW_LINE
    private var colorStart = Color.parseColor("#00E5FF")
    private var colorEnd = Color.parseColor("#7C4DFF")
    private var rainbow = false
    private var baseThickness = 16f
    private var speed = 1f

    private val bandCount = 32
    private var bands = FloatArray(bandCount)
    private val displayBands = FloatArray(bandCount)

    @Volatile
    private var level = 0f
    private var displayLevel = 0f
    private var rotationDeg = 0f
    private var hueShift = 0f
    private var lastActiveTime = 0L
    private var visibility01 = 0f

    private var shader: SweepGradient? = null
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private val hsv = floatArrayOf(0f, 1f, 1f)

    companion object {
        private const val SOUND_THRESHOLD = 0.06f
        private const val HOLD_MS = 1200L
        private val RAINBOW = intArrayOf(
            Color.parseColor("#FF1744"), Color.parseColor("#FF9100"),
            Color.parseColor("#FFEA00"), Color.parseColor("#00E676"),
            Color.parseColor("#00E5FF"), Color.parseColor("#2979FF"),
            Color.parseColor("#D500F9"), Color.parseColor("#FF1744")
        )
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun applySettings(
        style: Int, cStart: Int, cEnd: Int,
        isRainbow: Boolean, thickness: Float, spd: Float
    ) {
        styleId = style
        colorStart = cStart
        colorEnd = cEnd
        rainbow = isRainbow
        baseThickness = thickness
        speed = spd
        shader = null
        postInvalidate()
    }

    fun setAudioData(l: Float, newBands: FloatArray) {
        level = l.coerceIn(0f, 1f)
        if (newBands.size == bandCount) bands = newBands
        if (level > SOUND_THRESHOLD) {
            lastActiveTime = SystemClock.elapsedRealtime()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = null
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
        val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
        return Color.rgb(
            (ar + (br - ar) * tt).toInt(),
            (ag + (bg - ag) * tt).toInt(),
            (ab + (bb - ab) * tt).toInt()
        )
    }

    /** Color along the gradient. In rainbow mode this walks the full hue wheel and slowly rotates. */
    private fun colorAt(t: Float): Int {
        return if (rainbow) {
            hsv[0] = (hueShift + t * 300f) % 360f
            hsv[1] = 1f
            hsv[2] = 1f
            Color.HSVToColor(hsv)
        } else {
            lerpColor(colorStart, colorEnd, t)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val soundActive =
            SystemClock.elapsedRealtime() - lastActiveTime < HOLD_MS
        val target = if (soundActive) 1f else 0f
        visibility01 += (target - visibility01) * (if (soundActive) 0.25f else 0.10f)

        if (visibility01 < 0.02f) {
            visibility01 = 0f
            alpha = 0f
            postDelayed({ invalidate() }, 200)
            return
        }
        alpha = visibility01

        // Punchy equalizer motion: fast attack, slower musical decay
        for (i in 0 until bandCount) {
            val t = bands[i]
            if (t > displayBands[i]) {
                displayBands[i] += (t - displayBands[i]) * 0.55f
            } else {
                displayBands[i] *= 0.82f
            }
        }
        displayLevel += (level - displayLevel) * 0.30f
        rotationDeg += 0.6f * speed + displayLevel * 4f
        if (rotationDeg > 360f) rotationDeg -= 360f
        hueShift += 0.5f * speed + displayLevel * 1.5f
        if (hueShift > 360f) hueShift -= 360f

        when (styleId) {
            GlowStyles.SIDE_BARS -> drawSideBars(canvas)
            GlowStyles.BARS_AROUND -> drawBarsAround(canvas)
            GlowStyles.CORNER_GLOW -> drawCornerGlow(canvas)
            else -> drawGlowLine(canvas)
        }

        postInvalidateOnAnimation()
    }

    private fun drawGlowLine(canvas: Canvas) {
        if (shader == null) {
            val colors = if (rainbow) RAINBOW
            else intArrayOf(colorStart, colorEnd, colorStart)
            shader = SweepGradient(width / 2f, height / 2f, colors, null)
        }
        shaderMatrix.setRotate(rotationDeg, width / 2f, height / 2f)
        shader?.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        val thickness = baseThickness * (0.5f + displayLevel * 1.6f)
        paint.strokeWidth = max(4f, thickness)
        paint.maskFilter = BlurMaskFilter(max(2f, thickness * 0.9f), BlurMaskFilter.Blur.NORMAL)

        val half = paint.strokeWidth / 2f
        rect.set(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, 64f, 64f, paint)
    }

    private fun drawSideBars(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(2f, baseThickness * 0.4f), BlurMaskFilter.Blur.NORMAL)

        val n = 24
        val gap = height / n.toFloat()
        val barH = max(4f, baseThickness * 0.6f)
        for (i in 0 until n) {
            // Bass at the bottom, treble at the top - like a real equalizer
            val band = (n - 1 - i) * bandCount / n
            val mag = displayBands[band]
            val len = 8f + mag * width * 0.32f
            val cy = gap * i + gap / 2f
            paint.color = colorAt(i / (n - 1f))
            rect.set(0f, cy - barH / 2f, len, cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
            rect.set(width - len, cy - barH / 2f, width.toFloat(), cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
        }
    }

    private fun drawBarsAround(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(2f, baseThickness * 0.35f), BlurMaskFilter.Blur.NORMAL)

        val barH = max(4f, baseThickness * 0.55f)

        val nSide = 20
        val gapV = height / nSide.toFloat()
        for (i in 0 until nSide) {
            val band = (nSide - 1 - i) * bandCount / nSide
            val mag = displayBands[band]
            val len = 6f + mag * width * 0.24f
            val cy = gapV * i + gapV / 2f
            paint.color = colorAt(i / (nSide - 1f))
            rect.set(0f, cy - barH / 2f, len, cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
            rect.set(width - len, cy - barH / 2f, width.toFloat(), cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
        }

        val nTop = 14
        val gapH = width / nTop.toFloat()
        for (i in 0 until nTop) {
            val mag = displayBands[(i * 2 + 4) % bandCount]
            val len = 6f + mag * height * 0.13f
            val cx = gapH * i + gapH / 2f
            paint.color = colorAt(1f - i / (nTop - 1f))
            rect.set(cx - barH / 2f, 0f, cx + barH / 2f, len)
            canvas.drawRoundRect(rect, barH, barH, paint)
            rect.set(cx - barH / 2f, height - len, cx + barH / 2f, height.toFloat())
            canvas.drawRoundRect(rect, barH, barH, paint)
        }
    }

    private fun drawCornerGlow(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        val thickness = max(5f, baseThickness * (0.7f + displayLevel * 1.4f))
        paint.strokeWidth = thickness
        paint.maskFilter = BlurMaskFilter(max(3f, thickness * 0.8f), BlurMaskFilter.Blur.NORMAL)

        val r = 90f + displayLevel * 210f
        val off = thickness

        paint.color = colorAt(0f)
        rect.set(off, off, off + 2 * r, off + 2 * r)
        canvas.drawArc(rect, 180f, 90f, false, paint)

        paint.color = colorAt(0.33f)
        rect.set(width - off - 2 * r, off, width - off, off + 2 * r)
        canvas.drawArc(rect, 270f, 90f, false, paint)

        paint.color = colorAt(0.66f)
        rect.set(width - off - 2 * r, height - off - 2 * r, width - off, height - off)
        canvas.drawArc(rect, 0f, 90f, false, paint)

        paint.color = colorAt(1f)
        rect.set(off, height - off - 2 * r, off + 2 * r, height - off)
        canvas.drawArc(rect, 90f, 90f, false, paint)
    }
}
