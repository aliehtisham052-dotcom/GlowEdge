package com.innovation313.glowedge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.max

class EdgeVisualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var colors = intArrayOf(-16716033, -8630785, -16716033)
    private var baseThickness = 16f
    private var speed = 1f

    @Volatile
    private var level = 0f
    private var displayLevel = 0f
    private var rotationDeg = 0f
    private var shader: SweepGradient? = null
    private val shaderMatrix = Matrix()

    init {
        // BlurMaskFilter needs software rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun applyProfile(p: Profile) {
        colors = intArrayOf(p.colorStart, p.colorEnd, p.colorStart)
        baseThickness = p.thickness
        speed = p.speed
        shader = null
        postInvalidate()
    }

    fun setLevel(l: Float) {
        level = l.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        displayLevel += (level - displayLevel) * 0.25f
        rotationDeg += 0.6f * speed + displayLevel * 4f
        if (rotationDeg > 360f) rotationDeg -= 360f

        if (shader == null) {
            shader = SweepGradient(width / 2f, height / 2f, colors, null)
        }
        shaderMatrix.setRotate(rotationDeg, width / 2f, height / 2f)
        shader?.setLocalMatrix(shaderMatrix)
        paint.shader = shader

        val thickness = baseThickness * (0.5f + displayLevel * 1.6f)
        paint.strokeWidth = max(4f, thickness)
        paint.maskFilter = BlurMaskFilter(max(2f, thickness * 0.9f), BlurMaskFilter.Blur.NORMAL)

        val half = paint.strokeWidth / 2f
        val rect = RectF(half, half, width - half, height - half)
        canvas.drawRoundRect(rect, 64f, 64f, paint)

        postInvalidateOnAnimation()
    }
}
