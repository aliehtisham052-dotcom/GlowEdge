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
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * Renders the glowing edge effect. The glow is only visible while there is sound to
 * react to; on silence it fades to nothing. It never self-animates on its own, so a
 * quiet screen stays dark (music-only behavior is enforced by the service that feeds it).
 */
class EdgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var styleId = Styles.GLOW_LINE
    private var colorStart = Color.parseColor("#00E5FF")
    private var colorEnd = Color.parseColor("#7C4DFF")
    private var rainbow = false
    private var baseThickness = 16f
    private var speed = 1f
    private var intensity = 1f
    private var saver = false

    private val bandCount = 32
    private var bands = FloatArray(bandCount)
    private val disp = FloatArray(bandCount)

    @Volatile private var level = 0f
    private var dispLevel = 0f
    private var spin = 0f
    private var hue = 0f
    private var flame = 0f
    private var lastActive = 0L
    private var visibility = 0f

    // notification flash
    private var flashColor = 0
    private var flashUntil = 0L
    private var flashStart = 0L

    // beat + ripple
    private var lastBeat = 0f
    private var bloom = 0f
    private val ripples = ArrayList<FloatArray>()
    private val cometTrail = ArrayList<Float>()

    // preview mode drives its own synthetic audio
    private var preview = false
    private var prevPhase = 0f

    companion object {
        private const val THRESHOLD = 0.05f
        private const val HOLD_MS = 1100L
        private val RAINBOW = intArrayOf(
            Color.parseColor("#FF1744"), Color.parseColor("#FF9100"),
            Color.parseColor("#FFEA00"), Color.parseColor("#00E676"),
            Color.parseColor("#00E5FF"), Color.parseColor("#2979FF"),
            Color.parseColor("#D500F9"), Color.parseColor("#FF1744")
        )
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    fun setPreview(on: Boolean) { preview = on }

    fun configure(styleId: Int, cStart: Int, cEnd: Int, rainbow: Boolean,
              thickness: Float, speed: Float, intensity: Float, saver: Boolean) {
        this.styleId = styleId
        this.colorStart = cStart
        this.colorEnd = cEnd
        this.rainbow = rainbow
        this.baseThickness = thickness
        this.speed = speed
        this.intensity = intensity
        this.saver = saver
        shader = null
        postInvalidate()
    }

    fun feed(level: Float, newBands: FloatArray) {
        this.level = level.coerceIn(0f, 1f)
        if (newBands.size == bandCount) bands = newBands
        if (level > THRESHOLD) lastActive = SystemClock.elapsedRealtime()
    }

    fun flash(color: Int) {
        flashColor = color
        flashStart = SystemClock.elapsedRealtime()
        flashUntil = flashStart + 2600L
        lastActive = SystemClock.elapsedRealtime()
        postInvalidate()
    }

    private var shader: SweepGradient? = null
    private val matrix = Matrix()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); shader = null
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        )
    }

    private val hsv = floatArrayOf(0f, 1f, 1f)
    private fun colorAt(t: Float): Int {
        return if (rainbow) {
            hsv[0] = (hue + t * 300f) % 360f
            hsv[1] = 1f
            hsv[2] = 1f
            Color.HSVToColor(hsv)
        } else {
            mix(colorStart, colorEnd, t)
        }
    }

    private fun corner(): Float = (width.coerceAtMost(height) * 0.09f).coerceIn(40f, 130f)

    private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return 1f - (1f - t) * (1f - t) }
    private fun shape(raw: Float): Float =
        Math.pow(raw.coerceIn(0f, 1f).toDouble(), 0.75).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val now = SystemClock.elapsedRealtime()

        // Preview generates its own gentle music so the box is always alive
        if (preview) {
            prevPhase += 0.09f
            var sum = 0f
            for (i in 0 until bandCount) {
                val v = 0.30f + 0.45f * sin(prevPhase * 2.2f + i * 0.55f) +
                        0.2f * sin(prevPhase * 0.7f + i * 0.2f)
                bands[i] = v.coerceIn(0f, 1f); sum += bands[i]
            }
            level = (sum / bandCount + 0.25f).coerceIn(0f, 1f)
            lastActive = now
        }

        val flashing = now < flashUntil
        val soundActive = now - lastActive < HOLD_MS
        val target = if (soundActive || flashing || preview) 1f else 0f
        visibility += (target - visibility) * (if (target > 0f) 0.25f else 0.10f)

        if (visibility < 0.02f && !flashing) {
            visibility = 0f; alpha = 0f
            postDelayed({ invalidate() }, 200)
            return
        }

        if (flashing) {
            drawFlash(canvas, now); alpha = 1f
            postInvalidateOnAnimation()
            if (!soundActive && !preview) return
        }
        alpha = if (flashing) 1f else visibility

        for (i in 0 until bandCount) {
            if (bands[i] > disp[i]) disp[i] += (bands[i] - disp[i]) * 0.55f else disp[i] *= 0.82f
        }
        dispLevel += (level - dispLevel) * 0.30f
        if (dispLevel - lastBeat > 0.16f) {
            bloom = 1f
            if (styleId == Styles.RIPPLE && ripples.size < 6) ripples.add(floatArrayOf(0f, dispLevel))
        }
        lastBeat = dispLevel
        bloom *= 0.90f
        spin += 0.6f * speed + dispLevel * 4f; if (spin > 360f) spin -= 360f
        hue += 0.5f * speed + dispLevel * 1.5f; if (hue > 360f) hue -= 360f
        flame += 0.08f * speed + dispLevel * 0.15f

        when (styleId) {
            Styles.SIDE_BARS -> sideBars(canvas)
            Styles.BARS_AROUND -> barsAround(canvas)
            Styles.CORNER_GLOW -> cornerGlow(canvas)
            Styles.EMBER -> ember(canvas)
            Styles.CHASE -> chase(canvas)
            Styles.PULSE -> pulse(canvas)
            Styles.DOTS -> dots(canvas)
            Styles.AURORA -> aurora(canvas)
            Styles.COMET -> comet(canvas)
            Styles.RIPPLE -> ripple(canvas)
            else -> glowLine(canvas)
        }
        postInvalidateOnAnimation()
    }

    private fun glowLine(c: Canvas) {
        if (shader == null)
            shader = SweepGradient(width / 2f, height / 2f,
                if (rainbow) RAINBOW else intArrayOf(colorStart, colorEnd, colorStart), null)
        matrix.setRotate(spin, width / 2f, height / 2f)
        shader?.setLocalMatrix(matrix)
        paint.shader = shader; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val loud = ease(dispLevel)
        val t = baseThickness * (0.4f + loud * 1.9f * intensity)
        paint.strokeWidth = max(3f, t)
        paint.maskFilter = BlurMaskFilter(max(2f, t * (if (saver) 0.5f else 0.9f)), BlurMaskFilter.Blur.NORMAL)
        val ins = t * 0.3f
        rect.set(ins, ins, width - ins, height - ins)
        c.drawRoundRect(rect, corner(), corner(), paint)
    }

    private fun sideBars(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(2f, baseThickness * (if (saver) 0.25f else 0.4f)), BlurMaskFilter.Blur.NORMAL)
        val n = 26; val gap = height / n.toFloat(); val bh = max(5f, baseThickness * 0.55f)
        val minL = width * 0.03f; val maxL = width * 0.20f
        for (i in 0 until n) {
            val mag = shape(disp[(n - 1 - i) * bandCount / n])
            val len = minL + mag * (maxL - minL) * intensity
            val cy = gap * i + gap / 2f
            paint.color = colorAt(i / (n - 1f))
            rect.set(0f, cy - bh / 2f, len, cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
            rect.set(width - len, cy - bh / 2f, width.toFloat(), cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
        }
    }

    private fun barsAround(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(2f, baseThickness * 0.35f), BlurMaskFilter.Blur.NORMAL)
        val bh = max(4f, baseThickness * 0.55f)
        val nS = 22; val gV = height / nS.toFloat(); val mnV = width * 0.025f; val mxV = width * 0.16f
        for (i in 0 until nS) {
            val mag = shape(disp[(nS - 1 - i) * bandCount / nS])
            val len = mnV + mag * (mxV - mnV) * intensity; val cy = gV * i + gV / 2f
            paint.color = colorAt(i / (nS - 1f))
            rect.set(0f, cy - bh / 2f, len, cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
            rect.set(width - len, cy - bh / 2f, width.toFloat(), cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
        }
        val nT = 16; val gH = width / nT.toFloat(); val mnH = height * 0.02f; val mxH = height * 0.10f
        for (i in 0 until nT) {
            val mag = shape(disp[(i * 2 + 4) % bandCount])
            val len = mnH + mag * (mxH - mnH) * intensity; val cx = gH * i + gH / 2f
            paint.color = colorAt(1f - i / (nT - 1f))
            rect.set(cx - bh / 2f, 0f, cx + bh / 2f, len); c.drawRoundRect(rect, bh, bh, paint)
            rect.set(cx - bh / 2f, height - len, cx + bh / 2f, height.toFloat()); c.drawRoundRect(rect, bh, bh, paint)
        }
    }

    private fun cornerGlow(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val t = max(5f, baseThickness * (0.7f + dispLevel * 1.4f))
        paint.strokeWidth = t
        paint.maskFilter = BlurMaskFilter(max(3f, t * 0.8f), BlurMaskFilter.Blur.NORMAL)
        val r = 90f + dispLevel * 210f; val o = t * 0.4f
        paint.color = colorAt(0f); rect.set(o, o, o + 2 * r, o + 2 * r); c.drawArc(rect, 180f, 90f, false, paint)
        paint.color = colorAt(0.33f); rect.set(width - o - 2 * r, o, width - o, o + 2 * r); c.drawArc(rect, 270f, 90f, false, paint)
        paint.color = colorAt(0.66f); rect.set(width - o - 2 * r, height - o - 2 * r, width - o, height - o); c.drawArc(rect, 0f, 90f, false, paint)
        paint.color = colorAt(1f); rect.set(o, height - o - 2 * r, o + 2 * r, height - o); c.drawArc(rect, 90f, 90f, false, paint)
    }

    private fun ember(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        val n = 28; val gap = height / n.toFloat(); val mn = width * 0.03f; val mx = width * 0.19f
        for (i in 0 until n) {
            val mag = shape(disp[i * bandCount / n])
            val flick = 0.7f + 0.3f * sin(flame * 3f + i * 0.7f)
            val len = (mn + mag * (mx - mn) * intensity) * flick
            val cy = gap * i + gap / 2f; val bh = max(6f, baseThickness * 0.9f)
            paint.maskFilter = BlurMaskFilter(max(4f, len * 0.35f), BlurMaskFilter.Blur.NORMAL)
            val tt = mag.coerceIn(0f, 1f)
            paint.color = if (rainbow) colorAt(i / (n - 1f)) else mix(Color.parseColor("#FFEA00"), colorEnd, 1f - tt)
            rect.set(0f, cy - bh / 2f, len, cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
            paint.color = if (rainbow) colorAt((i + 4) / (n - 1f)) else mix(Color.parseColor("#FF6D00"), colorStart, 1f - tt)
            rect.set(width - len, cy - bh / 2f, width.toFloat(), cy + bh / 2f); c.drawRoundRect(rect, bh, bh, paint)
        }
    }

    private val pt = FloatArray(2)
    private fun onPerimeter(dist: Float): FloatArray {
        val w = width.toFloat(); val h = height.toFloat(); var d = dist
        if (d < w) { pt[0] = d; pt[1] = 0f; return pt }; d -= w
        if (d < h) { pt[0] = w; pt[1] = d; return pt }; d -= h
        if (d < w) { pt[0] = w - d; pt[1] = h; return pt }; d -= w
        pt[0] = 0f; pt[1] = h - d; return pt
    }

    private fun chase(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(4f, baseThickness * 0.7f), BlurMaskFilter.Blur.NORMAL)
        val per = 2f * (width + height); val count = 5 + intensity.toInt()
        val r = max(6f, baseThickness * (0.6f + dispLevel)); val travel = (flame * 120f) % per
        for (k in 0 until count) {
            val d = (travel + k * per / count) % per; val p = onPerimeter(d)
            paint.color = colorAt(k / (count - 1f))
            c.drawCircle(p[0], p[1], r * (0.7f + dispLevel * 0.6f), paint)
        }
    }

    private fun pulse(c: Canvas) {
        if (shader == null) shader = SweepGradient(width / 2f, height / 2f,
            if (rainbow) RAINBOW else intArrayOf(colorStart, colorEnd, colorStart), null)
        matrix.setRotate(spin, width / 2f, height / 2f); shader?.setLocalMatrix(matrix)
        paint.shader = shader; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val t = baseThickness * (0.6f + ease(dispLevel) * 3f * intensity)
        paint.strokeWidth = max(4f, t)
        paint.maskFilter = BlurMaskFilter(max(2f, t * (if (saver) 0.5f else 1.2f)), BlurMaskFilter.Blur.NORMAL)
        val ins = t * 0.3f; rect.set(ins, ins, width - ins, height - ins)
        c.drawRoundRect(rect, corner(), corner(), paint)
    }

    private fun dots(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.maskFilter = BlurMaskFilter(max(3f, baseThickness * (if (saver) 0.3f else 0.8f)), BlurMaskFilter.Blur.NORMAL)
        val per = 2f * (width + height); val count = 26
        for (k in 0 until count) {
            val mag = disp[k * bandCount / count]; val d = (k.toFloat() / count) * per; val p = onPerimeter(d)
            paint.color = colorAt(k / (count - 1f))
            c.drawCircle(p[0], p[1], max(3f, baseThickness * (0.4f + mag * 1.6f * intensity)), paint)
        }
    }

    private fun aurora(c: Canvas) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val layers = if (saver) 3 else 5
        for (l in 0 until layers) {
            val lf = l / (layers - 1f); val wob = sin(flame * 1.3f + l * 1.1f) * 0.5f + 0.5f
            val t = (hue / 360f + lf * 0.3f) % 1f
            paint.color = if (rainbow) colorAt(t) else mix(colorStart, colorEnd, (lf + wob) * 0.5f)
            paint.alpha = (90 + 60 * wob).toInt().coerceIn(40, 180)
            val base = baseThickness * (0.8f + dispLevel * 1.5f + bloom * 0.6f)
            paint.strokeWidth = max(3f, base * (1f - lf * 0.5f))
            paint.maskFilter = BlurMaskFilter(max(6f, base * (1.5f + lf)), BlurMaskFilter.Blur.NORMAL)
            val ins = 6f + l * 10f + wob * 12f; rect.set(ins, ins, width - ins, height - ins)
            c.drawRoundRect(rect, corner(), corner(), paint)
        }
        paint.alpha = 255
    }

    private fun comet(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.FILL
        val per = 2f * (width + height); cometTrail.add(0, (flame * 200f) % per)
        val maxTrail = if (saver) 14 else 26
        while (cometTrail.size > maxTrail) cometTrail.removeAt(cometTrail.size - 1)
        for (cc in 0 until 2) {
            val off = cc * per / 2
            for (i in cometTrail.indices) {
                val fade = 1f - i / cometTrail.size.toFloat()
                val p = onPerimeter((cometTrail[i] + off) % per)
                val r = max(2f, baseThickness * (0.7f + dispLevel) * fade)
                paint.color = colorAt((cc * 0.5f + fade) % 1f)
                paint.alpha = (fade * 230).toInt().coerceIn(0, 255)
                paint.maskFilter = BlurMaskFilter(max(3f, r * 1.2f), BlurMaskFilter.Blur.NORMAL)
                c.drawCircle(p[0], p[1], r, paint)
            }
        }
        paint.alpha = 255
    }

    private fun ripple(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val it = ripples.iterator()
        while (it.hasNext()) {
            val rp = it.next(); rp[0] += 0.02f * (1f + speed)
            if (rp[0] >= 1f) { it.remove(); continue }
            val prog = rp[0]
            paint.color = colorAt(prog)
            paint.alpha = ((1f - prog) * 200f * (0.4f + rp[1])).toInt().coerceIn(0, 220)
            val t = max(3f, baseThickness * (1f - prog) * 1.8f); paint.strokeWidth = t
            paint.maskFilter = BlurMaskFilter(max(4f, t * 1.2f), BlurMaskFilter.Blur.NORMAL)
            val ins = t * 0.5f + prog * (baseThickness * 2.2f)
            rect.set(ins, ins, width - ins, height - ins)
            c.drawRoundRect(rect, corner(), corner(), paint)
        }
        paint.alpha = (60 + bloom * 120).toInt().coerceIn(40, 200)
        paint.color = colorAt(0f)
        val base = max(3f, baseThickness * (0.5f + bloom)); paint.strokeWidth = base
        paint.maskFilter = BlurMaskFilter(max(3f, base), BlurMaskFilter.Blur.NORMAL)
        val ins = base * 0.5f; rect.set(ins, ins, width - ins, height - ins)
        c.drawRoundRect(rect, corner(), corner(), paint); paint.alpha = 255
    }

    private fun drawFlash(c: Canvas, now: Long) {
        val t = ((now - flashStart) / 2600f).coerceIn(0f, 1f)
        val env = sin(t * Math.PI.toFloat() * 2f).let { if (it < 0f) 0f else it }
        val breathe = (0.4f + 0.6f * env) * (1f - t)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        val t2 = baseThickness * (1.2f + breathe * 2.2f); paint.strokeWidth = max(6f, t2)
        paint.color = flashColor
        paint.maskFilter = BlurMaskFilter(max(6f, t2 * 1.1f), BlurMaskFilter.Blur.NORMAL)
        val ins = t2 * 0.3f; rect.set(ins, ins, width - ins, height - ins)
        c.drawRoundRect(rect, corner(), corner(), paint)
    }
}
