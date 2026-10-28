package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.audiology2.stream.R
import com.audiology2.stream.dsp.dbDisplay
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spectrogram view using the SRP spectral gradient.
 * Renders FFT magnitude data as a time-frequency heatmap.
 */
class SpectrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = 24f
        color = 0xFFFFFFFF.toInt()
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = 1f
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 0.5f
    }
    private val paintSelection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF00D4AA.toInt()
    }

    // SRP Spectral Gradient (intensity 0.0 → 1.0)
    /** SRP-fused spectral gradient. Index 0 = weakest, last = strongest.
     *  Strongest stop is hunter orange (#FF4F00), the SRP signal color. */
    private val spectralStops = intArrayOf(
        0xFF060119.toInt(),  // #060119 - dark navy (weakest)
        0xFF2F076E.toInt(),  // #2F076E - violet
        0xFF370150.toInt(),  // #370150 - deep purple
        0xFF5633C7.toInt(),  // #5633C7 - blue-purple
        0xFF134E1A.toInt(),  // #134E1A - dark green
        0xFFFF4F00.toInt()   // #FF4F00 - hunter orange (strongest)
    )

    // Data
    private var spectrogramData: Array<FloatArray> = emptyArray() // [time][freq] = magnitude 0-1
    private var sampleRate = 48000
    private var fftSize = 2048
    private var maxFreqHz = 20000.0
    private var minFreqHz = 20.0

    // View state
    private var scaleX = 1f
    private var scaleY = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var selectionStart = -1f
    private var selectionEnd = -1f
    private var isSelecting = false

    // Cached bitmap for performance
    private var cachedBitmap: Bitmap? = null
    private var dataVersion = 0
    private var lastDrawWidth = 0
    private var lastDrawHeight = 0

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Update spectrogram data. Called from audio thread. */
    fun updateSpectrogram(newData: Array<FloatArray>, sampleRate: Int, fftSize: Int) {
        this.spectrogramData = newData
        this.sampleRate = sampleRate
        this.fftSize = fftSize
        this.maxFreqHz = (sampleRate / 2).toDouble()
        dataVersion++
        cachedBitmap = null // Invalidate cache
        postInvalidate()
    }

    /** Set frequency range to display */
    fun setFrequencyRange(minHz: Double, maxHz: Double) {
        minFreqHz = minHz
        maxFreqHz = maxHz
        cachedBitmap = null
        postInvalidate()
    }

    /** Get color from spectral gradient for intensity 0-1 */
    private fun getSpectralColor(intensity: Float): Int {
        val clamped = intensity.coerceIn(0f, 1f)
        val segmentCount = spectralStops.size - 1
        val segment = (clamped * segmentCount).toInt().coerceIn(0, segmentCount - 1)
        val localT = clamped * segmentCount - segment

        val c1 = spectralStops[segment]
        val c2 = spectralStops[segment + 1]

        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF
        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF

        val r = (r1 + (r2 - r1) * localT).roundToInt()
        val g = (g1 + (g2 - g1) * localT).roundToInt()
        val b = (b1 + (b2 - b1) * localT).roundToInt()

        val colorInt = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return colorInt
    }

    /** Build or reuse cached spectrogram bitmap */
    private fun ensureBitmap(width: Int, height: Int): Bitmap {
        if (cachedBitmap != null && cachedBitmap!!.width == width && cachedBitmap!!.height == height) {
            return cachedBitmap!!
        }
        cachedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cachedBitmap!!)
        drawSpectrogramToCanvas(canvas, width, height)
        return cachedBitmap!!
    }

    /** Draw spectrogram data to canvas */
    private fun drawSpectrogramToCanvas(canvas: Canvas, width: Int, height: Int) {
        if (spectrogramData.isEmpty()) return

        val timeFrames = spectrogramData.size
        val freqBins = spectrogramData[0].size

        // Frequency bin to Hz mapping (linear for simplicity, could be log)
        val binToHz = maxFreqHz / freqBins
        val minBin = (minFreqHz / binToHz).toInt().coerceIn(0, freqBins - 1)
        val maxBin = (maxFreqHz / binToHz).toInt().coerceIn(minBin + 1, freqBins)
        val visibleBins = maxBin - minBin

        // Draw each time frame as a vertical line
        val pxPerFrame = width.toFloat() / timeFrames
        val pxPerBin = height.toFloat() / visibleBins

        for (t in 0 until timeFrames) {
            val frame = spectrogramData[t]
            val x = t * pxPerFrame + translateX * scaleX

            // Skip if outside view
            if (x < -pxPerFrame || x > width + pxPerFrame) continue

            for (b in 0 until visibleBins) {
                val bin = minBin + b
                val magnitude = frame[bin].coerceIn(0f, 1f)
                val color = getSpectralColor(magnitude)

                val y = height - (b + 1) * pxPerBin + translateY * scaleY
                paint.color = color
                canvas.drawRect(
                    x,
                    y,
                    x + pxPerFrame * scaleX + 1,
                    y + pxPerBin * scaleY + 1,
                    paint
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Background
        canvas.drawColor(0xFF0F0A18.toInt()) // --srp-bg

        // Draw cached spectrogram bitmap
        if (spectrogramData.isNotEmpty()) {
            val bmp = ensureBitmap(width.roundToInt(), height.roundToInt())
            canvas.save()
            canvas.scale(scaleX, scaleY, width / 2, height / 2)
            canvas.translate(translateX, translateY)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
        }

        // Grid lines
        drawGrid(canvas, width, height)

        // Frequency axis (Y)
        drawFrequencyAxis(canvas, width, height)

        // Time axis (X)
        drawTimeAxis(canvas, width, height)

        // Selection rectangle
        if (isSelecting && selectionStart >= 0 && selectionEnd >= 0) {
            val left = min(selectionStart, selectionEnd)
            val right = max(selectionStart, selectionEnd)
            canvas.drawRect(left, 0f, right, height, paintSelection)
        }
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        // Horizontal grid lines (frequency)
        val hLines = 5
        for (i in 1 until hLines) {
            val y = height * i / hLines
            canvas.drawLine(0f, y, width, y, paintGrid)
        }
        // Vertical grid lines (time)
        val vLines = 10
        for (i in 1 until vLines) {
            val x = width * i / vLines
            canvas.drawLine(x, 0f, x, height, paintGrid)
        }
    }

    private fun drawFrequencyAxis(canvas: Canvas, width: Float, height: Float) {
        val labels = 6
        for (i in 0 until labels) {
            val y = height * i / (labels - 1)
            val freqHz = maxFreqHz - (maxFreqHz - minFreqHz) * i / (labels - 1)
            val label = if (freqHz >= 1000) "${(freqHz / 1000).toInt()}k" else "${freqHz.toInt()}"
            canvas.drawText(label, 4f, y + 8f, paintText)
            canvas.drawLine(0f, y, 30f, y, paintAxis)
        }
        canvas.drawText("Hz", 4f, 20f, paintText)
    }

    private fun drawTimeAxis(canvas: Canvas, width: Float, height: Float) {
        val durationSec = if (spectrogramData.isNotEmpty()) {
            spectrogramData.size.toDouble() * fftSize / sampleRate
        } else 0.0

        val labels = 6
        for (i in 0 until labels) {
            val x = width * i / (labels - 1)
            val timeSec = durationSec * i / (labels - 1)
            val label = String.format("%.1fs", timeSec)
            canvas.drawText(label, x, height - 4f, paintText)
            canvas.drawLine(x, height, x, height - 30f, paintAxis)
        }
        canvas.drawText("Time", width - 60f, height - 4f, paintText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isSelecting = true
                selectionStart = event.x
                selectionEnd = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    // Pinch zoom
                    val focusX = scaleDetector.focusX
                    val focusY = scaleDetector.focusY
                    val newScaleX = (scaleX * scaleDetector.scaleFactor).coerceIn(0.5f, 20f)
                    val newScaleY = (scaleY * scaleDetector.scaleFactor).coerceIn(0.5f, 20f)

                    // Adjust translation to zoom around focus point
                    translateX -= (focusX - width / 2f) * (newScaleX / scaleX - 1)
                    translateY -= (focusY - height / 2f) * (newScaleY / scaleY - 1)

                    scaleX = newScaleX
                    scaleY = newScaleY
                } else {
                    // Pan or select
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 20) {
                        translateX += dx
                        translateY += dy
                    }
                    selectionEnd = event.x
                }
                lastTouchX = event.x
                lastTouchY = event.y
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSelecting = false
                if (Math.abs(selectionEnd - selectionStart) < 50) {
                    selectionStart = -1f
                    selectionEnd = -1f
                } else {
                    getSelectionTimeRange()?.let { onSelectionEnd?.invoke(it) }
                }
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            return true
        }
    }

    /** Convert pixel X to time in seconds */
    fun pixelToTime(px: Float): Double {
        val durationSec = if (spectrogramData.isNotEmpty()) {
            spectrogramData.size.toDouble() * fftSize / sampleRate
        } else 0.0
        val adjustedPx = (px - translateX) / scaleX
        return (adjustedPx / width) * durationSec
    }

    /** Convert pixel Y to frequency in Hz */
    fun pixelToFreq(px: Float): Double {
        val adjustedPx = (px - translateY) / scaleY
        val ratio = 1f - adjustedPx / height
        return minFreqHz + ratio * (maxFreqHz - minFreqHz)
    }

    /** Get current selection range in seconds */
    fun getSelectionTimeRange(): Pair<Double, Double>? {
        if (selectionStart < 0 || selectionEnd < 0) return null
        val start = pixelToTime(min(selectionStart, selectionEnd))
        val end = pixelToTime(max(selectionStart, selectionEnd))
        return start to end
    }

    /** Clear selection */
    fun clearSelection() {
        selectionStart = -1f
        selectionEnd = -1f
        postInvalidate()
    }

    /** Fired when a drag selection completes: (startSec, endSec) of the stream. */
    var onSelectionEnd: ((Pair<Double, Double>) -> Unit)? = null
}