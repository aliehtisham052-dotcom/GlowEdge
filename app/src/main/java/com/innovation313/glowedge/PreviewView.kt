package com.innovation313.glowedge

import android.content.Context
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** A small in-app preview that animates the currently selected style with synthetic beats. */
class PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context) {

    private val edge = EdgeVisualizerView(context)
    private var phase = 0f
    private val bands = FloatArray(32)

    init {
        // Nothing to add; we draw by delegating to edge's canvas methods via its draw.
    }

    fun refresh() {
        val theme = ProfileManager.theme(context)
        edge.applySettings(
            ProfileManager.style(context),
            theme.colorStart, theme.colorEnd, theme.rainbow,
            ProfileManager.thickness(context).toFloat() * 0.7f,
            ProfileManager.speed(context) / 10f,
            ProfileManager.intensity(context) / 10f,
            false
        )
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edge.layout(0, 0, w, h)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // synthetic music so the preview always looks alive
        phase += 0.08f
        var sum = 0f
        for (i in bands.indices) {
            val beat = 0.35f + 0.4f * sin(phase * 2f + i * 0.4f) + Random.nextFloat() * 0.15f
            bands[i] = beat.coerceIn(0f, 1f)
            sum += bands[i]
        }
        edge.setAudioData((sum / bands.size).coerceIn(0f, 1f), bands)
        edge.draw(canvas)
        postInvalidateOnAnimation()
    }
}
