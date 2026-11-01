package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Spectroid-style spectrum analyzer:
 *  - top: live amplitude-vs-frequency spectrum with a peak-hold trace,
 *  - bottom: scrolling waterfall (frequency on X, time scrolling down),
 *  - a marker labelling the dominant frequency in Hz.
 *
 * Feed it magnitude frames (0..1 bins) from the capture FFT via [push].
 */
class SpectrumAnalyzerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val COLS = 256
    private val ROWS = 96

    private var sampleRate = 48000
    private var fftSize = 2048
    private var binCount = 1024

    /** Waterfall ring: newest row at the bottom (scrolling down). */
    private val waterfall = FloatArray(COLS * ROWS)
    private var haveData = false

    private val current = FloatArray(COLS)
    private val peakHold = FloatArray(COLS)

    private var peakHz = 0.0
    private var peakLevel = 0f

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paintSpectrum = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintSpectrumLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xFF12F012.toInt()
    }
    private val paintPeakHold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0xFFFFB020.toInt()
    }
    private val paintGrid = Paint().apply {
        color = 0xFF3D2D61.toInt()
        strokeWidth = 1f
    }
    private val paintAxisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9698AB.toInt()
        textSize = dp(9f)
    }
    private val paintMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4F00.toInt()
        strokeWidth = dp(1.5f)
        textSize = dp(10f)
    }

    private val spectrumPath = Path()
    private val peakPath = Path()

    private var bmp: Bitmap? = null
    private val pixels = IntArray(COLS * ROWS)

    private var spectrumGradient: LinearGradient? = null
    private var gradientW = 0f

    fun push(frame: FloatArray, sr: Int, ffts: Int) {
        if (frame.isEmpty()) return
        sampleRate = sr
        fftSize = ffts
        binCount = frame.size

        // Downsample the spectrum to COLS columns (peak within each group).
        val group = max(1, binCount / COLS)
        for (c in 0 until COLS) {
            val start = c * group
            if (start >= binCount) {
                current[c] = 0f
                continue
            }
            val end = ((c + 1) * group).coerceAtMost(binCount)
            var m = 0f
            for (i in start until end) if (frame[i] > m) m = frame[i]
            current[c] = m
            peakHold[c] = if (m >= peakHold[c]) m else (peakHold[c] - 0.015f).coerceAtLeast(m)
        }

        // Dominant partial (skip DC).
        var best = 1
        var bestV = 0f
        for (i in 1 until binCount) {
            if (frame[i] > bestV) {
                bestV = frame[i]
                best = i
            }
        }
        peakLevel = bestV
        peakHz = best.toDouble() * sampleRate / fftSize

        // Scroll waterfall: shift one row, write new at bottom.
        System.arraycopy(waterfall, COLS, waterfall, 0, COLS * (ROWS - 1))
        System.arraycopy(current, 0, waterfall, COLS * (ROWS - 1), COLS)
        haveData = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF0F0A18.toInt())
        if (!haveData) {
            paintAxisText.textSize = dp(11f)
            canvas.drawText("waiting for signal…", dp(10f), h / 2f, paintAxisText)
            paintAxisText.textSize = dp(9f)
            return
        }

        val spectrumH = h * 0.52f
        val waterfallTop = spectrumH + dp(4f)
        val axisH = dp(14f)
        val waterfallBottom = h - axisH
        val waterfallH = (waterfallBottom - waterfallTop).coerceAtLeast(1f)

        drawGrid(canvas, w, spectrumH, waterfallTop, waterfallBottom)
        drawSpectrum(canvas, w, spectrumH)
        drawWaterfall(canvas, w, waterfallTop, waterfallH)
        drawFrequencyAxis(canvas, w, h, axisH)
        drawPeakMarker(canvas, w, spectrumH)
    }

    private fun drawGrid(canvas: Canvas, w: Float, spectrumH: Float, wfTop: Float, wfBottom: Float) {
        // Frequency grid (5 verticals)
        for (i in 1 until 5) {
            val x = w * i / 5f
            canvas.drawLine(x, 0f, x, spectrumH, paintGrid)
            canvas.drawLine(x, wfTop, x, wfBottom, paintGrid)
        }
        // Amplitude grid (4 horizontals in the spectrum)
        for (i in 1 until 4) {
            val y = spectrumH * i / 4f
            canvas.drawLine(0f, y, w, y, paintGrid)
        }
    }

    private fun drawSpectrum(canvas: Canvas, w: Float, spectrumH: Float) {
        spectrumPath.reset()
        peakPath.reset()
        spectrumPath.moveTo(0f, spectrumH)
        peakPath.moveTo(0f, spectrumH - peakHold[0] * spectrumH)
        for (c in 0 until COLS) {
            val x = w * c / (COLS - 1).toFloat()
            spectrumPath.lineTo(x, spectrumH - current[c] * spectrumH)
            peakPath.lineTo(x, spectrumH - peakHold[c] * spectrumH)
        }
        spectrumPath.lineTo(w, spectrumH)
        spectrumPath.close()

        if (spectrumGradient == null || gradientW != w) {
            gradientW = w
            spectrumGradient = LinearGradient(
                0f, 0f, 0f, spectrumH,
                intArrayOf(0xFFFF4F00.toInt(), 0xFF12F012.toInt(), 0xFF00D1FF.toInt(), 0xFF370150.toInt()),
                floatArrayOf(0f, 0.35f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        paintSpectrum.shader = spectrumGradient
        paintSpectrum.alpha = 200
        canvas.drawPath(spectrumPath, paintSpectrum)
        paintSpectrum.alpha = 255
        canvas.drawPath(spectrumPath, paintSpectrumLine)
        canvas.drawPath(peakPath, paintPeakHold)
    }

    private fun drawWaterfall(canvas: Canvas, w: Float, top: Float, height: Float) {
        val b = ensureBitmap()
        // Build pixel colors from magnitudes.
        for (r in 0 until ROWS) {
            val rowBase = r * COLS
            for (c in 0 until COLS) {
                pixels[rowBase + c] = colorFor(waterfall[rowBase + c])
            }
        }
        b.setPixels(pixels, 0, COLS, 0, 0, COLS, ROWS)
        val rect = android.graphics.RectF(0f, top, w, top + height)
        canvas.drawBitmap(b, null, rect, null)
    }

    private fun ensureBitmap(): Bitmap {
        val cur = bmp
        if (cur != null && cur.width == COLS && cur.height == ROWS) return cur
        val created = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888)
        bmp = created
        return created
    }

    private fun drawFrequencyAxis(canvas: Canvas, w: Float, h: Float, axisH: Float) {
        val nyquist = sampleRate / 2.0
        for (i in 0..4) {
            val frac = i / 4f
            val hz = nyquist * frac
            val label = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
            val x = (w * frac).coerceIn(0f, w - dp(18f))
            canvas.drawLine(w * frac, h - axisH, w * frac, h - axisH + dp(3f), paintGrid)
            canvas.drawText(label, x, h - dp(3f), paintAxisText)
        }
    }

    private fun drawPeakMarker(canvas: Canvas, w: Float, spectrumH: Float) {
        if (peakLevel < 0.02f) return
        val nyquist = sampleRate / 2.0
        val x = (peakHz / nyquist * w).toFloat().coerceIn(0f, w)
        canvas.drawLine(x, 0f, x, spectrumH, paintMarker)
        val label = if (peakHz >= 1000) String.format("%.2f kHz", peakHz / 1000.0) else String.format("%.0f Hz", peakHz)
        val tx = if (x > w * 0.7f) x - dp(64f) else x + dp(3f)
        canvas.drawText(label, tx, dp(11f), paintMarker)
    }

    /** SRP-fused waterfall color: dark → violet → cyan → green → hunter orange. */
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
