package com.innovation313.glowedge

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.max

class EdgeVisualizerView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cached blur filters: creating a new BlurMaskFilter every frame causes memory
    // churn and jank on budget phones; reusing them keeps the glow smooth and light.
    private val blurCache = HashMap<Int, BlurMaskFilter>()
    private fun blur(radius: Float): BlurMaskFilter {
        val key = radius.toInt().coerceAtLeast(1)
        return blurCache.getOrPut(key) { BlurMaskFilter(key.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    private var styleId = GlowStyles.GLOW_LINE
    private var colorStart = Color.parseColor("#6BD4E8")
    private var colorEnd = Color.parseColor("#7C4DFF")
    private var rainbow = false
    private var baseThickness = 16f
    private var speed = 1f
    private var intensity = 1f
    private var flamePhase = 0f
    private var batterySaver = false
    private val cometTrail = ArrayList<Float>()
    private val ripples = ArrayList<FloatArray>()  // each: [progress, startLevel]
    private var lastBeatLevel = 0f
    private var beatBloom = 0f
    // Rhythm engine: bass-onset beat detection + tempo tracking + loudness envelope
    private var bassAvg = 0.06f
    private var lastBeatTime = 0L
    private var beatIntervalMs = 500f     // ~120 BPM start
    private var rhythmMul = 1f            // animation speed follows the track's tempo
    private var loudEnv = 0f              // fast-attack, slow-release loudness
    private var introStart = 0L
    private var introActive = false

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
    private var flashColor = 0
    private var flashUntil = 0L
    private var flashStart = 0L

    private var shader: SweepGradient? = null
    private val shaderMatrix = Matrix()
    private val rect = RectF()
    private val hsv = floatArrayOf(0f, 1f, 1f)

    companion object {
        private const val SOUND_THRESHOLD = 0.06f
        private const val HOLD_MS = 1200L
        private val RAINBOW = intArrayOf(
            Color.parseColor("#EF6E7A"), Color.parseColor("#F2A96B"),
            Color.parseColor("#EFD97A"), Color.parseColor("#6BD9A0"),
            Color.parseColor("#6BD4E8"), Color.parseColor("#7BA3E8"),
            Color.parseColor("#C68BE0"), Color.parseColor("#EF6E7A")
        )
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Play the corner-sweep intro pattern when the glow activates. */
    fun startIntro() {
        introStart = SystemClock.elapsedRealtime()
        introActive = true
        lastActiveTime = SystemClock.elapsedRealtime()
        postInvalidate()
    }

    fun applySettings(
        style: Int, cStart: Int, cEnd: Int,
        isRainbow: Boolean, thickness: Float, spd: Float, inten: Float, saver: Boolean
    ) {
        styleId = style
        colorStart = cStart
        colorEnd = cEnd
        rainbow = isRainbow
        baseThickness = thickness
        speed = spd
        intensity = inten
        batterySaver = saver
        shader = null
        postInvalidate()
    }

    fun setAudioData(l: Float, newBands: FloatArray) {
        level = l.coerceIn(0f, 1f)
        if (newBands.size == bandCount) bands = newBands
        if (level > SOUND_THRESHOLD) {
            lastActiveTime = SystemClock.elapsedRealtime()
            lastRealData = SystemClock.elapsedRealtime()
        }
    }

    private var lastRealData = 0L
    private var demoMode = false
    private var demoPhase = 0f

    /** Force a self-animating demo glow (used when the device blocks audio capture). */
    fun setDemoMode(on: Boolean) { demoMode = on }

    private var testUntil = 0L
    private var sweepStart = 0L
    private var sweepUntil = 0L
    private val segPath = Path()

    /** One-time Innovation-313 signature sweep around the edge (plays on style change). */
    fun startSignatureSweep() {
        sweepStart = SystemClock.elapsedRealtime()
        sweepUntil = sweepStart + 2800L
        postInvalidate()
    }
    /** Show a guaranteed bright glow for a few seconds to verify the overlay works. */
    fun forceTestGlow() {
        testUntil = SystemClock.elapsedRealtime() + 5000L
        // NOTE: demoMode is intentionally NOT set here. The test window (testUntil)
        // drives its own drawing; leaving demoMode on would keep the glow animating
        // forever after the test, wasting CPU and hanging low-end phones.
        lastActiveTime = SystemClock.elapsedRealtime()
        lastRealData = SystemClock.elapsedRealtime()
        introActive = false
        postInvalidate()
    }

    /** Flash a colored glow around the edges for a couple of seconds (call/notification). */
    fun triggerNotificationFlash(color: Int) {
        flashColor = color
        flashStart = SystemClock.elapsedRealtime()
        flashUntil = flashStart + 2600L
        lastActiveTime = SystemClock.elapsedRealtime()
        postInvalidate()
    }

    /** Longer pulsing flash for phone calls (keeps glowing while ringing). */
    fun triggerCallFlash(color: Int) {
        flashColor = color
        flashStart = SystemClock.elapsedRealtime()
        flashUntil = flashStart + 8000L
        lastActiveTime = SystemClock.elapsedRealtime()
        postInvalidate()
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
            hsv[1] = 0.78f
            hsv[2] = 0.95f
            Color.HSVToColor(hsv)
        } else {
            lerpColor(colorStart, colorEnd, t)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val now = SystemClock.elapsedRealtime()

        // ---- Innovation-313 signature sweep (one-time, on style change) ----
        if (now < sweepUntil) {
            visibility01 = 1f
            alpha = 1f
            drawSignatureSweep(canvas, now)
            postInvalidateDelayed(33L)
            return
        }

        // ---- Test glow: guaranteed bright glow to verify overlay works ----
        if (now < testUntil) {
            visibility01 = 1f
            alpha = 1f
            demoPhase += 0.06f
            var sum = 0f
            for (i in 0 until bandCount) {
                val v = 0.6f + 0.4f * kotlin.math.sin(demoPhase * 2f + i * 0.5f)
                bands[i] = v.coerceIn(0f,1f); sum += v
            }
            level = 0.85f
            displayLevel = 0.85f
            for (i in 0 until bandCount) displayBands[i] = bands[i]
            when (styleId) {
                GlowStyles.SIDE_BARS -> drawSideBars(canvas)
                GlowStyles.BARS_AROUND -> drawBarsAround(canvas)
                GlowStyles.CORNER_GLOW -> drawCornerGlow(canvas)
                GlowStyles.EMBER -> drawEmber(canvas)
                GlowStyles.CHASE -> drawChase(canvas)
                GlowStyles.PULSE -> drawPulse(canvas)
                GlowStyles.DOTS -> drawDots(canvas)
                GlowStyles.AURORA -> drawAurora(canvas)
                GlowStyles.COMET -> drawComet(canvas)
                GlowStyles.RIPPLE -> drawRipple(canvas)
            GlowStyles.SEGMENTS -> drawSegments(canvas)
                GlowStyles.SEGMENTS -> drawSegments(canvas)
                else -> drawGlowLine(canvas)
            }
            postInvalidateDelayed(33L)
            return
        }

        // ---- Intro animation: corner sweep lines -> settle into style ----
        if (introActive) {
            val dur = 1500f
            val t = ((now - introStart) / dur).coerceIn(0f, 1f)
            alpha = 1f
            drawIntro(canvas, t)
            if (t >= 1f) introActive = false
            postInvalidateDelayed(33L)
            return
        }

        val flashing = now < flashUntil

        // Demo animation runs ONLY if explicitly enabled (device truly blocks audio),
        // never just because it is quiet. This keeps the screen dark on silence / normal voice.
        if (demoMode) {
            demoPhase += 0.06f
            var sum = 0f
            for (i in 0 until bandCount) {
                val v = 0.35f + 0.35f * kotlin.math.sin(demoPhase * 2f + i * 0.5f)
                bands[i] = v.coerceIn(0f, 1f); sum += v
            }
            level = (sum / bandCount)
            lastActiveTime = now
        }

        val soundActive = now - lastActiveTime < HOLD_MS
        val target = if (soundActive || flashing) 1f else 0f
        visibility01 += (target - visibility01) * (if (target > 0f) 0.25f else 0.10f)

        if (visibility01 < 0.02f && !flashing) {
            visibility01 = 0f
            alpha = 0f
            postDelayed({ invalidate() }, 200)
            return
        }

        // Draw notification flash on its own (works without music)
        if (flashing) {
            drawNotificationFlash(canvas, now)
            alpha = 1f
            postInvalidateDelayed(33L)
            if (!soundActive) return
        }
        alpha = if (flashing) 1f else visibility01 * (0.55f + 0.45f * loudEnv.coerceIn(0f, 1f))

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

        // ---- Rhythm engine ----
        // Bass onset detection: beat = bass energy jumping clearly above its own average
        var bassNow = 0f
        for (i in 0 until 8) bassNow += displayBands[i]
        bassNow /= 8f
        bassAvg += (bassNow - bassAvg) * 0.06f            // slow-moving average
        val sinceBeat = now - lastBeatTime
        if (bassNow > bassAvg * 1.35f && bassNow > 0.12f && sinceBeat > 240L) {
            beatBloom = 1f
            if (lastBeatTime != 0L && sinceBeat < 2000L) {
                // learn the track's tempo (smoothed inter-beat interval)
                beatIntervalMs += (sinceBeat - beatIntervalMs) * 0.25f
            }
            lastBeatTime = now
            if (styleId == GlowStyles.RIPPLE && ripples.size < 6) {
                ripples.add(floatArrayOf(0f, displayLevel))
            }
        }
        // Animation speed follows the rhythm: fast songs animate faster, slow naats glide
        rhythmMul += ((500f / beatIntervalMs.coerceIn(280f, 1200f)).coerceIn(0.6f, 1.8f) - rhythmMul) * 0.05f
        // Every style gets a beat punch (bloom briefly lifts the whole glow)
        displayLevel = (displayLevel + beatBloom * 0.22f).coerceAtMost(1f)
        lastBeatLevel = displayLevel
        beatBloom *= 0.90f

        // Loudness envelope: glow brightness follows the voice's loudness
        // (fast attack so hits light up instantly, slow release so it breathes out)
        loudEnv = if (level > loudEnv) loudEnv + (level - loudEnv) * 0.5f
                  else loudEnv * 0.94f

        rotationDeg += (0.6f * speed + displayLevel * 4f) * rhythmMul
        if (rotationDeg > 360f) rotationDeg -= 360f
        hueShift += (0.5f * speed + displayLevel * 1.5f) * rhythmMul
        if (hueShift > 360f) hueShift -= 360f
        flamePhase += (0.08f * speed + displayLevel * 0.15f) * rhythmMul

        when (styleId) {
            GlowStyles.SIDE_BARS -> drawSideBars(canvas)
            GlowStyles.BARS_AROUND -> drawBarsAround(canvas)
            GlowStyles.CORNER_GLOW -> drawCornerGlow(canvas)
            GlowStyles.EMBER -> drawEmber(canvas)
            GlowStyles.CHASE -> drawChase(canvas)
            GlowStyles.PULSE -> drawPulse(canvas)
            GlowStyles.DOTS -> drawDots(canvas)
            GlowStyles.AURORA -> drawAurora(canvas)
            GlowStyles.COMET -> drawComet(canvas)
            GlowStyles.RIPPLE -> drawRipple(canvas)
            GlowStyles.SEGMENTS -> drawSegments(canvas)
            else -> drawGlowLine(canvas)
        }

        postInvalidateDelayed(33L)
    }

    private fun drawPulse(canvas: Canvas) {
        if (shader == null) {
            val colors = if (rainbow) RAINBOW else intArrayOf(colorStart, colorEnd, colorStart)
            shader = SweepGradient(width / 2f, height / 2f, colors, null)
        }
        shaderMatrix.setRotate(rotationDeg, width / 2f, height / 2f)
        shader?.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val loud = easeOut(displayLevel)
        val thickness = baseThickness * (0.6f + loud * 3.0f * intensity)
        paint.strokeWidth = max(4f, thickness)
        val blurMul = if (batterySaver) 0.5f else 1.2f
        paint.maskFilter = blur(max(2f, thickness * blurMul))
        val inset = paint.strokeWidth * 0.3f
        rect.set(inset, inset, width - inset, height - inset)
        val corner = screenCornerRadius()
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    private fun drawDots(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        val blurMul = if (batterySaver) 0.3f else 0.8f
        paint.maskFilter = blur(max(3f, baseThickness * blurMul))
        val perimeter = 2f * (width + height)
        val dotCount = 26
        for (k in 0 until dotCount) {
            val band = displayBands[k * bandCount / dotCount]
            val d = (k.toFloat() / dotCount) * perimeter
            val p = pointOnPerimeter(d)
            val r = max(3f, baseThickness * (0.4f + band * 1.6f * intensity))
            paint.color = colorAt(k / (dotCount - 1f))
            canvas.drawCircle(p[0], p[1], r, paint)
        }
    }

    private fun drawAurora(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val layers = if (batterySaver) 3 else 5
        val corner = screenCornerRadius()
        for (layer in 0 until layers) {
            val lf = layer / (layers - 1f)
            val wobble = kotlin.math.sin(flamePhase * 1.3f + layer * 1.1f) * 0.5f + 0.5f
            val t = (hueShift / 360f + lf * 0.3f) % 1f
            paint.color = if (rainbow) colorAt(t) else lerpColor(colorStart, colorEnd, (lf + wobble) * 0.5f)
            paint.alpha = (90 + 60 * wobble).toInt().coerceIn(40, 180)
            val base = baseThickness * (0.8f + displayLevel * 1.5f + beatBloom * 0.6f)
            paint.strokeWidth = max(3f, base * (1f - lf * 0.5f))
            paint.maskFilter = blur(max(6f, base * (1.5f + lf)))
            val inset = 6f + layer * 10f + wobble * 12f
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        paint.alpha = 255
    }

    private fun drawComet(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        val perimeter = 2f * (width + height)
        val head = (flamePhase * 200f) % perimeter
        cometTrail.add(0, head)
        val maxTrail = if (batterySaver) 14 else 26
        while (cometTrail.size > maxTrail) cometTrail.removeAt(cometTrail.size - 1)

        val comets = 2
        for (c in 0 until comets) {
            val offset = c * perimeter / comets
            for (i in cometTrail.indices) {
                val fade = 1f - i / cometTrail.size.toFloat()
                val d = (cometTrail[i] + offset) % perimeter
                val p = pointOnPerimeter(d)
                val r = max(2f, baseThickness * (0.7f + displayLevel) * fade)
                paint.color = colorAt((c * 0.5f + fade) % 1f)
                paint.alpha = (fade * 230).toInt().coerceIn(0, 255)
                paint.maskFilter = blur(max(3f, r * 1.2f))
                canvas.drawCircle(p[0], p[1], r, paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawRipple(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val corner = screenCornerRadius()
        val it = ripples.iterator()
        while (it.hasNext()) {
            val rp = it.next()
            rp[0] += 0.02f * (1f + speed)
            if (rp[0] >= 1f) { it.remove(); continue }
            val prog = rp[0]
            val alpha = ((1f - prog) * 200f * (0.4f + rp[1])).toInt().coerceIn(0, 220)
            paint.color = colorAt(prog)
            paint.alpha = alpha
            val thickness = max(3f, baseThickness * (1f - prog) * 1.8f)
            paint.strokeWidth = thickness
            paint.maskFilter = blur(max(4f, thickness * 1.2f))
            // Ripple stays at the edge: only a small inward travel so it never reaches the middle
            val inset = thickness * 0.5f + prog * (baseThickness * 2.2f)
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        paint.alpha = 255
        // Always keep a subtle base frame so it is not empty between beats
        paint.color = colorAt(0f)
        paint.alpha = (60 + beatBloom * 120).toInt().coerceIn(40, 200)
        val base = max(3f, baseThickness * (0.5f + beatBloom))
        paint.strokeWidth = base
        paint.maskFilter = blur(max(3f, base))
        val inset = base * 0.5f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.alpha = 255
    }

    private fun drawIntro(canvas: Canvas, t: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val corner = screenCornerRadius()
        // Ease: lines grow from the 4 corners along the edges, meeting in the middle
        val ease = 1f - (1f - t) * (1f - t)
        val w = width.toFloat(); val h = height.toFloat()
        val thickness = baseThickness * (1.2f + (1f - t) * 1.5f)
        paint.strokeWidth = max(4f, thickness)
        paint.maskFilter = blur(max(4f, thickness))

        // fraction of each edge covered, growing from both corners toward center
        val hCover = (w / 2f) * ease
        val vCover = (h / 2f) * ease
        val inset = thickness * 0.5f

        // Top edge (from both corners inward)
        paint.color = colorAt(0f)
        canvas.drawLine(inset, inset, inset + hCover, inset, paint)
        canvas.drawLine(w - inset, inset, w - inset - hCover, inset, paint)
        // Bottom edge
        paint.color = colorAt(0.5f)
        canvas.drawLine(inset, h - inset, inset + hCover, h - inset, paint)
        canvas.drawLine(w - inset, h - inset, w - inset - hCover, h - inset, paint)
        // Left edge
        paint.color = colorAt(0.25f)
        canvas.drawLine(inset, inset, inset, inset + vCover, paint)
        canvas.drawLine(inset, h - inset, inset, h - inset - vCover, paint)
        // Right edge
        paint.color = colorAt(0.75f)
        canvas.drawLine(w - inset, inset, w - inset, inset + vCover, paint)
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - vCover, paint)

        // Near the end, fade a full rounded frame in so it settles smoothly into the style
        if (t > 0.7f) {
            val ff = (t - 0.7f) / 0.3f
            paint.alpha = (ff * 255).toInt().coerceIn(0, 255)
            paint.color = colorAt(0f)
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.alpha = 255
        }
    }

    private fun drawNotificationFlash(canvas: Canvas, now: Long) {
        val total = 2600f
        val t = ((now - flashStart) / total).coerceIn(0f, 1f)
        // Breathing envelope: rises quickly, gently falls; two soft pulses
        val env = kotlin.math.sin(t * Math.PI.toFloat() * 2f).let { if (it < 0f) 0f else it }
        val breathe = (0.4f + 0.6f * env) * (1f - t)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val thickness = baseThickness * (1.2f + breathe * 2.2f)
        paint.strokeWidth = max(6f, thickness)
        paint.color = flashColor
        paint.maskFilter = blur(max(6f, thickness * 1.1f))
        val inset = paint.strokeWidth * 0.3f
        rect.set(inset, inset, width - inset, height - inset)
        val corner = screenCornerRadius()
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    private fun drawGlowLine(canvas: Canvas) {
        if (shader == null) {
            val colors = if (rainbow) RAINBOW
            else intArrayOf(colorStart, blendByLoudness(), colorEnd, blendByLoudness(), colorStart)
            shader = SweepGradient(width / 2f, height / 2f, colors, null)
        } else if (!rainbow) {
            // Rebuild gradient each frame so the mid color tracks loudness (quiet = cool, loud = hot)
            shader = SweepGradient(
                width / 2f, height / 2f,
                intArrayOf(colorStart, blendByLoudness(), colorEnd, blendByLoudness(), colorStart), null
            )
        }
        shaderMatrix.setRotate(rotationDeg, width / 2f, height / 2f)
        shader?.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // Thickness follows loudness with an eased curve + intensity control
        val loud = easeOut(displayLevel)
        val thickness = baseThickness * (0.35f + loud * 2.0f * intensity)
        paint.strokeWidth = max(3f, thickness)
        // Brighter, wider glow when loud
        paint.maskFilter = blur(max(2f, thickness * (0.7f + loud)))

        // Sit flush against the true screen edge and match phone's rounded corners
        val inset = paint.strokeWidth * 0.28f
        rect.set(inset, inset, width - inset, height - inset)
        val corner = screenCornerRadius()
        canvas.drawRoundRect(rect, corner, corner, paint)
    }

    private fun screenCornerRadius(): Float {
        // Approximate modern phone screen corner radius; scales with screen size
        return (width.coerceAtMost(height) * 0.09f).coerceIn(40f, 130f)
    }

    /** Eased 0..1 curve so quiet stays subtle and loud pops - like a designer's response curve. */
    private fun easeOut(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    /** Shapes a raw band into a pleasing bar magnitude: gentle floor, smooth mid, soft ceiling. */
    private fun shapeMag(raw: Float): Float {
        val t = raw.coerceIn(0f, 1f)
        // slight gamma so small sounds still show, loud ones don't max out too flat
        val g = Math.pow(t.toDouble(), 0.75).toFloat()
        return g.coerceIn(0f, 1f)
    }

    /** Mid gradient color that warms up as the music gets louder. */
    private fun blendByLoudness(): Int {
        val loud = easeOut(displayLevel)
        // quiet -> lean toward colorStart (cool), loud -> push toward a hot accent
        val hot = lerpColor(colorEnd, Color.parseColor("#FFFFFF"), loud * 0.35f)
        return lerpColor(colorStart, hot, loud)
    }

    private fun drawSideBars(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.maskFilter = blur(max(2f, baseThickness * 0.4f))

        val n = 26
        val gap = height / n.toFloat()
        val barH = max(5f, baseThickness * 0.55f)
        val minLen = width * 0.03f          // always visible
        val maxLen = width * 0.20f          // never overwhelming
        for (i in 0 until n) {
            // Bass at the bottom, treble at the top - like a real equalizer
            val band = (n - 1 - i) * bandCount / n
            val mag = shapeMag(displayBands[band])
            val len = minLen + mag * (maxLen - minLen) * intensity
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
        paint.maskFilter = blur(max(2f, baseThickness * 0.35f))

        val barH = max(4f, baseThickness * 0.55f)

        val nSide = 22
        val gapV = height / nSide.toFloat()
        val minV = width * 0.025f
        val maxV = width * 0.16f
        for (i in 0 until nSide) {
            val band = (nSide - 1 - i) * bandCount / nSide
            val mag = shapeMag(displayBands[band])
            val len = minV + mag * (maxV - minV) * intensity
            val cy = gapV * i + gapV / 2f
            paint.color = colorAt(i / (nSide - 1f))
            rect.set(0f, cy - barH / 2f, len, cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
            rect.set(width - len, cy - barH / 2f, width.toFloat(), cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
        }

        val nTop = 16
        val gapH = width / nTop.toFloat()
        val minH = height * 0.02f
        val maxH = height * 0.10f
        for (i in 0 until nTop) {
            val mag = shapeMag(displayBands[(i * 2 + 4) % bandCount])
            val len = minH + mag * (maxH - minH) * intensity
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
        paint.maskFilter = blur(max(3f, thickness * 0.8f))

        val r = 90f + displayLevel * 210f
        val off = thickness * 0.4f

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

    private fun drawEmber(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        val n = 28
        val gap = height / n.toFloat()
        val minE = width * 0.03f
        val maxE = width * 0.19f
        for (i in 0 until n) {
            val mag = shapeMag(displayBands[i * bandCount / n])
            val flick = 0.7f + 0.3f * kotlin.math.sin(flamePhase * 3f + i * 0.7f)
            val len = (minE + mag * (maxE - minE) * intensity) * flick
            val cy = gap * i + gap / 2f
            val barH = max(6f, baseThickness * 0.9f)
            paint.maskFilter = blur(max(4f, len * 0.35f))
            // Fire gradient: hot yellow core to red-purple tips
            val t = mag.coerceIn(0f, 1f)
            paint.color = if (rainbow) colorAt(i / (n - 1f))
                else lerpColor(Color.parseColor("#EFD97A"), colorEnd, 1f - t)
            rect.set(0f, cy - barH / 2f, len, cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
            paint.color = if (rainbow) colorAt((i + 4) / (n - 1f))
                else lerpColor(Color.parseColor("#FF6D00"), colorStart, 1f - t)
            rect.set(width - len, cy - barH / 2f, width.toFloat(), cy + barH / 2f)
            canvas.drawRoundRect(rect, barH, barH, paint)
        }
    }

    private fun drawChase(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.maskFilter = blur(max(4f, baseThickness * 0.7f))

        val perimeter = 2f * (width + height)
        val dotCount = 5 + (intensity).toInt()
        val dotR = max(6f, baseThickness * (0.6f + displayLevel))
        val travel = (flamePhase * 120f) % perimeter

        for (k in 0 until dotCount) {
            val d = (travel + k * perimeter / dotCount) % perimeter
            val p = pointOnPerimeter(d)
            paint.color = colorAt(k / (dotCount - 1f))
            canvas.drawCircle(p[0], p[1], dotR * (0.7f + displayLevel * 0.6f), paint)
        }
    }

    private val ptOut = FloatArray(2)
    /** Edge split into segments; each segment dances to its own frequency band. */
    private fun drawSegments(canvas: Canvas) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val per = 2f * (width + height)
        val nSeg = 14
        val segLen = per / nSeg
        val gap = segLen * 0.22f
        val step = per / 220f
        for (i in 0 until nSeg) {
            val band = displayBands[i * bandCount / nSeg]
            val t = max(4f, baseThickness * (0.45f + band * 1.6f * intensity))
            paint.strokeWidth = t
            paint.maskFilter = BlurMaskFilter(max(3f, t * (if (batterySaver) 0.4f else 0.8f)), BlurMaskFilter.Blur.NORMAL)
            paint.color = colorAt(i / (nSeg - 1f))
            paint.alpha = (120 + band * 135).toInt().coerceIn(90, 255)
            val d0 = i * segLen + gap / 2f
            val d1 = (i + 1) * segLen - gap / 2f
            segPath.reset()
            var d = d0
            var first = true
            while (d <= d1) {
                val pnt = pointOnPerimeter(d % per)
                if (first) { segPath.moveTo(pnt[0], pnt[1]); first = false }
                else segPath.lineTo(pnt[0], pnt[1])
                d += step
            }
            val pEnd = pointOnPerimeter(d1 % per)
            segPath.lineTo(pEnd[0], pEnd[1])
            canvas.drawPath(segPath, paint)
        }
        paint.alpha = 255
    }

    /** One-time branded sweep: a glowing head + trail carries "Innovation-313" around
     *  the edge once, shifting color as it passes from one side to the other. */
    private fun drawSignatureSweep(canvas: Canvas, now: Long) {
        val per = 2f * (width + height)
        val t = ((now - sweepStart) / 2800f).coerceIn(0f, 1f)
        // ease in-out so it glides
        val e = t * t * (3f - 2f * t)
        val d = e * per
        // color shifts by position (side to side)
        val hue = (d / per) * 360f
        val col = Color.HSVToColor(floatArrayOf(hue % 360f, 0.72f, 0.95f))

        paint.shader = null
        paint.style = Paint.Style.FILL
        // trail
        val trail = if (batterySaver) 10 else 18
        for (i in 0 until trail) {
            val dd = d - i * 30f
            if (dd < 0f) break
            val fade = 1f - i / trail.toFloat()
            val pnt = pointOnPerimeter(dd % per)
            val r = max(4f, baseThickness * 0.8f * fade)
            paint.color = Color.HSVToColor(floatArrayOf(((dd / per) * 360f) % 360f, 0.72f, 0.95f))
            paint.alpha = (fade * 220).toInt().coerceIn(0, 255)
            paint.maskFilter = BlurMaskFilter(max(3f, r), BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(pnt[0], pnt[1], r, paint)
        }
        paint.alpha = 255

        // the developer tag, kept just inside the screen so it is always readable
        val head = pointOnPerimeter(d % per)
        paint.maskFilter = null
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 42f
        paint.textAlign = Paint.Align.CENTER
        val tx = head[0].coerceIn(150f, width - 150f)
        val ty = head[1].coerceIn(110f, height - 60f)
        paint.color = Color.argb(160, 10, 17, 40)
        canvas.drawText("Innovation-313", tx + 2f, ty + 2f, paint)  // soft shadow
        paint.color = col
        canvas.drawText("Innovation-313", tx, ty, paint)
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
    }

    private fun pointOnPerimeter(dist: Float): FloatArray {
        val w = width.toFloat(); val h = height.toFloat()
        var d = dist
        if (d < w) { ptOut[0] = d; ptOut[1] = 0f; return ptOut }
        d -= w
        if (d < h) { ptOut[0] = w; ptOut[1] = d; return ptOut }
        d -= h
        if (d < w) { ptOut[0] = w - d; ptOut[1] = h; return ptOut }
        d -= w
        ptOut[0] = 0f; ptOut[1] = h - d; return ptOut
    }

}
