package com.innovation313.glowedge

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Small in-app box that shows the currently selected style animating with synthetic audio. */
class PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val edge = EdgeView(context)
    private var lastStyle = -1
    private var lastTheme = -1

    init { edge.setPreview(true) }

    fun refresh() { applyNow(); invalidate() }

    private fun applyNow() {
        val t = Settings.theme(context)
        edge.configure(
            Settings.styleId(context), t.start, t.end, t.rainbow,
            Settings.thickness(context).toFloat() * 0.7f,
            Settings.speed(context) / 10f,
            Settings.intensity(context) / 10f,
            false
        )
        lastStyle = Settings.styleId(context); lastTheme = Settings.themeIndex(context)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); edge.layout(0, 0, w, h); applyNow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (Settings.styleId(context) != lastStyle || Settings.themeIndex(context) != lastTheme) applyNow()
        edge.draw(canvas)
        postInvalidateOnAnimation()
    }
}
