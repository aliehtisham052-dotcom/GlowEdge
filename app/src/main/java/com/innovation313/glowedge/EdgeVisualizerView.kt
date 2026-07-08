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
import kotlin.math.sin

class EdgeVisualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var styleId = GlowStyles.GLOW_LINE
    private var colorStart = Color.parseColor("#00E5FF")
    private var colorEnd = Color.parseColor("#7C4DFF")
    private var baseThickness = 16f
    private var speed = 1f

    private val bandCount = 32
    private var bands = FloatArray(bandCount)
    private val displayBands = FloatArray(bandCount)

    @Volatile
    private var level = 0f
    private var displayLevel = 0f
    private var rotationDeg = 0f
    private var phase = 0f
    private var lastDataTime = 0L

    private var shader: SweepGradient? = null
    private val shaderMatrix = Matrix()
    private val rect = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun applySettings(style: Int, cStart: Int, cEnd: Int, thickness: Float, spd: Float) {
        styleId = style
        colorStart = cStart
        colorEnd = cEnd
        baseThickness = thickness
        speed = spd
        shader = null
        postInvalidate()
    }

    fun setAudioData(l: Float, newBands: FloatArray) {
        level = l.coerceIn(0f, 1f)
        if (newBands.size == bandCount) bands = newBands
        lastDataTime = SystemClock.elapsedRealtime()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // Idle animation if no audio data is coming (restricted devices)
        val idle = SystemClock.elapsedRealtime() - lastDataTime > 1500
        if (idle) {
            for (i in 0 until bandCount) {
                bands[i] = 0.25f + 0.2f * sin(phase * 2f + i * 0.5f)
            }
            level = 0.3f + 0.1f * sin(phase * 1.5f)
        }

        for (i in 0 until bandCount) {
            displayBands[i] += (bands[i] - displayBands[i]) * 0.35f
        }
        displayLevel += (level - displayLevel) * 0.25f
        phase += 0.03f * speed
        rotationDeg += 0.6f * speed + displayLevel * 4f
        if (rotationDeg > 360f) rotationDeg -= 360f

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
            shader = SweepGradient(
                width / 2f, height / 2f,
                intArrayOf(colorStart, colorEnd, colorStart), null
            )
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
            val mag = displayBands[i * bandCount / n]
            val len = 14f + mag * width * 0.30f
            val cy = gap * i + gap / 2f
            paint.color = lerpColor(colorStart, colorEnd, i / (n - 1f))
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
            val mag = displayBands[i * bandCount / nSide]
            val len = 10f + mag * width * 0.22f
            val cy = gapV * i + gapV / 2f
            paint.color = lerpColor(colorStart, colorEnd, i / (nSide - 1f))
            rect.set(0f, cy - barH / 2f, len, cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
            rect.set(width - len, cy - barH / 2f, width.toFloat(), cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
        }

        val nTop = 14
        val gapH = width / nTop.toFloat()
        for (i in 0 until nTop) {
            val mag = displayBands[(i + 6) % bandCount]
            val len = 10f + mag * height * 0.12f
            val cx = gapH * i + gapH / 2f
            paint.color = lerpColor(colorEnd, colorStart, i / (nTop - 1f))
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

        val r = 110f + displayLevel * 190f
        val off = thickness

        paint.color = colorStart
        rect.set(off, off, off + 2 * r, off + 2 * r)
        canvas.drawArc(rect, 180f, 90f, false, paint)

        paint.color = lerpColor(colorStart, colorEnd, 0.4f)
        rect.set(width - off - 2 * r, off, width - off, off + 2 * r)
        canvas.drawArc(rect, 270f, 90f, false, paint)

        paint.color = colorEnd
        rect.set(width - off - 2 * r, height - off - 2 * r, width - off, height - off)
        canvas.drawArc(rect, 0f, 90f, false, paint)

        paint.color = lerpColor(colorStart, colorEnd, 0.7f)
        rect.set(off, height - off - 2 * r, off + 2 * r, height - off)
        canvas.drawArc(rect, 90f, 90f, false, paint)
    }
}
