package com.innovation313.glowedge

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/** A small in-app preview that animates the currently selected style with synthetic beats. */
class PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val edge = EdgeVisualizerView(context)
    private var phase = 0f
    private val bands = FloatArray(32)
    private var lastStyle = -1
    private var lastTheme = -1

    fun refresh() {
        applyNow()
        invalidate()
    }

    private fun applyNow() {
        val theme = ProfileManager.theme(context)
        edge.applySettings(
            ProfileManager.style(context),
            theme.colorStart, theme.colorEnd, theme.rainbow,
            ProfileManager.thickness(context).toFloat() * 0.7f,
            ProfileManager.speed(context) / 10f,
            ProfileManager.intensity(context) / 10f,
            false,
            ProfileManager.glowEdges(context)
        )
        lastStyle = ProfileManager.style(context)
        lastTheme = ProfileManager.themeIndex(context)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edge.layout(0, 0, w, h)
        applyNow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // Re-apply if the user changed style/theme (keeps preview always correct)
        if (ProfileManager.style(context) != lastStyle ||
            ProfileManager.themeIndex(context) != lastTheme) {
            applyNow()
        }

        // Rich synthetic music so different styles look clearly different
        phase += 0.09f
        var sum = 0f
        for (i in bands.indices) {
            val beat = 0.30f +
                0.45f * sin(phase * 2.2f + i * 0.55f) +
                0.20f * sin(phase * 0.7f + i * 0.2f) +
                Random.nextFloat() * 0.12f
            bands[i] = beat.coerceIn(0f, 1f)
            sum += bands[i]
        }
        edge.setAudioData((sum / bands.size + 0.25f).coerceIn(0f, 1f), bands)
        edge.draw(canvas)
        postInvalidateDelayed(40L)
    }
}
