package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive spectral view (Spectroid-style spectrum + a LATERAL waterfall).
 *
 *  - top: live amplitude-vs-frequency spectrum with a peak-hold trace and a
 *    dominant-frequency marker;
 *  - bottom: a lateral waterfall - TIME on X (newest at the right),
 *    FREQUENCY on Y (low at the bottom) - so a region can be
 *    highlighted/selected for spectral edits;
 *  - drag on the waterfall to select a time x frequency region; the host is
 *    notified with Hz bounds via [onSelection] and can read [selection].
 *
 * Fed magnitude frames (0..1 bins) from the capture FFT via [push].
 */
class SpectrumAnalyzerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val HISTORY = 220   // time columns (X)
    private val BANDS = 120     // frequency rows (Y)

    private var sampleRate = 48000
    private var fftSize = 2048
    private var binCount = 1024

    /** Waterfall history: column-major [time][band]; newest column at the right. */
    private val hist = FloatArray(HISTORY * BANDS)
    private var haveData = false

    private val spectrum = FloatArray(BANDS) // reused: downsampled magnitude per band
    private val peakHold = FloatArray(BANDS)
    private var peakHz = 0.0
    private var peakLevel = 0f

    // ---- Selection (view coordinates) ----
    private var selStartX = -1f
    private var selStartY = -1f
    private var selEndX = -1f
    private var selEndY = -1f
    private var isSelecting = false

    /** Selected frequency band in Hz (or null when nothing is selected). */
    val selectionFreq: Pair<Double, Double>?
        get() {
            if (selStartX < 0f) return null
            val (lo, hi) = yToFreq(min(selStartY, selEndY), max(selStartY, selEndY))
            return lo to hi
        }

    /** Selected time span as a 0..1 fraction of the visible history (or null). */
    val selectionTimeFraction: Pair<Float, Float>?
        get() {
            if (selStartX < 0f) return null
            val lo = (min(selStartX, selEndX) / width).coerceIn(0f, 1f)
            val hi = (max(selStartX, selEndX) / width).coerceIn(0f, 1f)
            return lo to hi
        }

    /** Notified when a selection completes: (timeLoFrac, timeHiFrac, freqLoHz, freqHiHz). */
    var onSelection: ((Float, Float, Double, Double) -> Unit)? = null

    fun clearSelection() {
        selStartX = -1f
        selStartY = -1f
        selEndX = -1f
        selEndY = -1f
        isSelecting = false
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paintSpectrum = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintSpectrumLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF12F012.toInt()
    }
    private val paintPeakHold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFFFFB020.toInt()
    }
    private val paintGrid = Paint().apply { color = 0xFF3D2D61.toInt(); strokeWidth = 1f }
    private val paintAxisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9698AB.toInt(); textSize = dp(9f)
    }
    private val paintMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4F00.toInt(); strokeWidth = dp(1.5f); textSize = dp(10f)
    }
    private val paintSelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5533FFAA }
    private val paintSelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF33FFAA.toInt()
    }

    private val spectrumPath = Path()
    private val peakPath = Path()
    private var bmp: Bitmap? = null
    private val pixels = IntArray(HISTORY * BANDS)
    private var spectrumGradient: LinearGradient? = null
    private var gradientW = 0f

    private var staticMode = false

    /** Feed a live frame (scrolling). Ignored while in static clip mode. */
    fun push(frame: FloatArray, sr: Int, ffts: Int) {
        if (staticMode || frame.isEmpty()) return
        sampleRate = sr
        fftSize = ffts
        binCount = frame.size
        applySpectrumFrame(frame)
        // Scroll the lateral waterfall left; write the newest column at the right.
        System.arraycopy(hist, BANDS, hist, 0, BANDS * (HISTORY - 1))
        System.arraycopy(spectrum, 0, hist, BANDS * (HISTORY - 1), BANDS)
        haveData = true
        postInvalidateOnAnimation()
    }

    /** Show a whole clip's STFT as a static lateral image (time X, freq Y). */
    fun showFrames(frames: Array<FloatArray>, sr: Int, fftSize: Int) {
        if (frames.isEmpty()) return
        staticMode = true
        this.sampleRate = sr
        this.fftSize = fftSize
        binCount = frames.first().size
        val n = frames.size
        val group = max(1, binCount / BANDS)
        for (t in 0 until HISTORY) {
            val fi = ((t.toLong() * n) / HISTORY).toInt().coerceIn(0, n - 1)
            val frame = frames[fi]
            val base = t * BANDS
            for (b in 0 until BANDS) {
                val s = b * group
                if (s >= binCount) { hist[base + b] = 0f; continue }
                val e = ((b + 1) * group).coerceAtMost(binCount)
                var m = 0f
                for (i in s until e) if (frame[i] > m) m = frame[i]
                hist[base + b] = m
            }
        }
        val meanFrame = FloatArray(binCount)
        for (f in frames) {
            val k2 = minOf(binCount, f.size)
            for (k in 0 until k2) meanFrame[k] += f[k]
        }
        for (k in meanFrame.indices) meanFrame[k] /= n.toFloat()
        applySpectrumFrame(meanFrame)
        haveData = true
        postInvalidate()
    }

    /** Return to live scrolling. */
    fun setLiveMode() { staticMode = false }

    private fun applySpectrumFrame(frame: FloatArray) {
        // Downsample spectrum to BANDS (freq) rows.
        val group = max(1, binCount / BANDS)
        for (b in 0 until BANDS) {
            val start = b * group
            if (start >= binCount) { spectrum[b] = 0f; continue }
            val end = ((b + 1) * group).coerceAtMost(binCount)
            var m = 0f
            for (i in start until end) if (frame[i] > m) m = frame[i]
            spectrum[b] = m
            peakHold[b] = if (m >= peakHold[b]) m else (peakHold[b] - 0.015f).coerceAtLeast(m)
        }
        // Dominant partial (skip DC).
        var best = 1; var bestV = 0f
        for (i in 1 until binCount) if (frame[i] > bestV) { bestV = frame[i]; best = i }
        peakLevel = bestV
        peakHz = best.toDouble() * sampleRate / fftSize
    }

    // ---- Touch: select a region on the waterfall ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val wfTop = height * 0.46f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.y < wfTop) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                selStartX = event.x; selStartY = event.y
                selEndX = event.x; selEndY = event.y
                isSelecting = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSelecting) return false
                selEndX = event.x.coerceIn(0f, width.toFloat())
                selEndY = event.y.coerceIn(wfTop, height.toFloat())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSelecting = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val tf = selectionTimeFraction
                val fq = selectionFreq
                if (tf != null && fq != null && (selEndX - selStartX) * (selEndY - selStartY) != 0f) {
                    onSelection?.invoke(tf.first, tf.second, fq.first, fq.second)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun yToFreq(yLo: Float, yHi: Float): Pair<Double, Double> {
        val wfTop = height * 0.46f
        val wfBottom = height.toFloat()
        val span = (wfBottom - wfTop).coerceAtLeast(1f)
        // top = high freq, bottom = low freq
        val fracHi = (1f - (yLo - wfTop) / span).coerceIn(0f, 1f)
        val fracLo = (1f - (yHi - wfTop) / span).coerceIn(0f, 1f)
        val nyq = sampleRate / 2.0
        return (fracLo * nyq) to (fracHi * nyq)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF0F0A18.toInt())
        if (!haveData) {
            canvas.drawText("waiting for signal…", dp(10f), h / 2f, paintAxisText)
            return
        }

        val spectrumH = h * 0.42f
        val wfTop = h * 0.46f
        val wfBottom = h - dp(4f)

        drawSpectrum(canvas, w, spectrumH)
        drawWaterfall(canvas, w, wfTop, wfBottom)
        drawAxes(canvas, w, h, spectrumH, wfTop, wfBottom)
        drawSelection(canvas, wfTop)
        drawPeakMarker(canvas, w, spectrumH)
    }

    private fun drawSpectrum(canvas: Canvas, w: Float, spectrumH: Float) {
        // grid
        for (i in 1 until 4) canvas.drawLine(0f, spectrumH * i / 4f, w, spectrumH * i / 4f, paintGrid)
        for (i in 1 until 5) canvas.drawLine(w * i / 5f, 0f, w * i / 5f, spectrumH, paintGrid)

        spectrumPath.reset(); peakPath.reset()
        spectrumPath.moveTo(0f, spectrumH)
        peakPath.moveTo(0f, spectrumH - peakHold[0] * spectrumH)
        for (b in 0 until BANDS) {
            val x = w * b / (BANDS - 1).toFloat()
            spectrumPath.lineTo(x, spectrumH - spectrum[b] * spectrumH)
            peakPath.lineTo(x, spectrumH - peakHold[b] * spectrumH)
        }
        spectrumPath.lineTo(w, spectrumH); spectrumPath.close()

        if (spectrumGradient == null || gradientW != w) {
            gradientW = w
            spectrumGradient = LinearGradient(
                0f, 0f, 0f, spectrumH,
                intArrayOf(0xFFFF4F00.toInt(), 0xFF12F012.toInt(), 0xFF00D1FF.toInt(), 0xFF370150.toInt()),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        }
        paintSpectrum.shader = spectrumGradient
        paintSpectrum.alpha = 200
        canvas.drawPath(spectrumPath, paintSpectrum)
        paintSpectrum.alpha = 255
        canvas.drawPath(spectrumPath, paintSpectrumLine)
        canvas.drawPath(peakPath, paintPeakHold)
    }

    private fun drawWaterfall(canvas: Canvas, w: Float, top: Float, bottom: Float) {
        val b = ensureBitmap()
        // Bitmap: row 0 (top) = highest band; column = time (newest right).
        for (t in 0 until HISTORY) {
            val colBase = t * BANDS
            for (bnd in 0 until BANDS) {
                val mag = hist[colBase + bnd]
                val row = BANDS - 1 - bnd
                pixels[row * HISTORY + t] = colorFor(mag)
            }
        }
        b.setPixels(pixels, 0, HISTORY, 0, 0, HISTORY, BANDS)
        canvas.drawBitmap(b, null, RectF(0f, top, w, bottom), null)
    }

    private fun ensureBitmap(): Bitmap {
        val cur = bmp
        if (cur != null && cur.width == HISTORY && cur.height == BANDS) return cur
        val created = Bitmap.createBitmap(HISTORY, BANDS, Bitmap.Config.ARGB_8888)
        bmp = created
        return created
    }

    private fun drawAxes(canvas: Canvas, w: Float, h: Float, spectrumH: Float, wfTop: Float, wfBottom: Float) {
        // Frequency labels for the spectrum (X) and the waterfall (Y).
        val nyq = sampleRate / 2.0
        for (i in 0..4) {
            val frac = i / 4f
            val hz = nyq * frac
            val label = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
            canvas.drawText(label, (w * frac).coerceIn(0f, w - dp(18f)), spectrumH + dp(9f), paintAxisText)
        }
        // Waterfall Y frequency labels (right edge)
        for (i in 0..4) {
            val frac = i / 4f
            val hz = nyq * frac
            val label = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
            val y = (wfBottom - (wfBottom - wfTop) * frac).coerceIn(wfTop + dp(9f), wfBottom)
            canvas.drawText(label, dp(3f), y, paintAxisText)
        }
    }

    private fun drawSelection(canvas: Canvas, wfTop: Float) {
        if (selStartX < 0f) return
        val left = min(selStartX, selEndX)
        val top = min(selStartY, selEndY).coerceAtLeast(wfTop)
        val right = max(selStartX, selEndX)
        val bottom = max(selStartY, selEndY)
        if (right - left < dp(2f) || bottom - top < dp(2f)) return
        val rect = RectF(left, top, right, bottom)
        canvas.drawRect(rect, paintSelFill)
        canvas.drawRect(rect, paintSelBorder)
    }

    private fun drawPeakMarker(canvas: Canvas, w: Float, spectrumH: Float) {
        if (peakLevel < 0.02f) return
        val x = (peakHz / (sampleRate / 2.0) * w).toFloat().coerceIn(0f, w)
        canvas.drawLine(x, 0f, x, spectrumH, paintMarker)
        val label = if (peakHz >= 1000) String.format("%.2f kHz", peakHz / 1000.0) else String.format("%.0f Hz", peakHz)
        val tx = if (x > w * 0.7f) x - dp(64f) else x + dp(3f)
        canvas.drawText(label, tx, dp(11f), paintMarker)
    }

    private fun colorFor(v: Float): Int {
        val t = v.coerceIn(0f, 1f)
        return when {
            t < 0.25f -> lerp(0xFF060119.toInt(), 0xFF370150.toInt(), t / 0.25f)
            t < 0.50f -> lerp(0xFF370150.toInt(), 0xFF5633C7.toInt(), (t - 0.25f) / 0.25f)
            t < 0.75f -> lerp(0xFF5633C7.toInt(), 0xFF00D1FF.toInt(), (t - 0.50f) / 0.25f)
            t < 0.90f -> lerp(0xFF00D1FF.toInt(), 0xFF12F012.toInt(), (t - 0.75f) / 0.15f)
            else -> lerp(0xFF12F012.toInt(), 0xFFFF4F00.toInt(), (t - 0.90f) / 0.10f)
        }
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        val alpha = if (t < 0.02f) 0 else 255
        return (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
