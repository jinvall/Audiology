package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.audiology2.stream.dsp.dbDisplay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Waveform view with zoom/pan, selection, and multi-track support.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintWaveform = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF00D4AA.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val paintWaveformFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4400D4AA.toInt()
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = 1f
        textAlign = Paint.Align.LEFT
        textSize = 22f
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 0.5f
    }
    private val paintSelection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4400D4AA.toInt()
    }
    private val paintCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF4D6D.toInt()
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCCCCCC.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    // Data
    private var waveformData: FloatArray = FloatArray(0) // Normalized -1..1
    private var sampleRate = 48000
    private var trackColor = 0xFF00D4AA.toInt()

    // View state
    private var scaleX = 1f
    private var translateX = 0f
    private var verticalZoom = 1f
    private var verticalOffset = 0f

    // Selection
    private var selectionStart = -1f
    private var selectionEnd = -1f
    private var isSelecting = false

    // Playback cursor
    private var cursorPosition = -1f // in samples
    private var cursorTime = -1.0

    // Decimated data for overview
    private var decimatedPeaks: FloatArray = FloatArray(0) // [min, max] pairs per pixel
    private var decimationFactor = 1

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastTouchX = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Set waveform data (normalized -1 to 1) */
    fun setWaveform(data: FloatArray, sampleRate: Int) {
        this.waveformData = data
        this.sampleRate = sampleRate
        buildDecimatedData()
        postInvalidate()
    }

    /** Set track color */
    fun setTrackColor(color: Int) {
        trackColor = color
        paintWaveform.color = color
        val fillColor = color.and(0x00FFFFFF).or(0x44000000)
        paintWaveformFill.color = fillColor
        paintCursor.color = color
        postInvalidate()
    }

    /** Update playback cursor position (in seconds) */
    fun setCursorTime(timeSec: Double) {
        cursorTime = timeSec
        if (sampleRate > 0) {
            cursorPosition = (timeSec * sampleRate).toFloat()
        }
        postInvalidate()
    }

    /** Clear cursor */
    fun clearCursor() {
        cursorPosition = -1f
        cursorTime = -1.0
        postInvalidate()
    }

    /** Build decimated peak data for fast overview rendering */
    private fun buildDecimatedData() {
        if (waveformData.isEmpty()) {
            decimatedPeaks = FloatArray(0)
            return
        }

        val targetPixels = 2000 // Max pixels to store
        decimationFactor = max(1, waveformData.size / targetPixels)
        val outputSize = (waveformData.size / decimationFactor) * 2
        decimatedPeaks = FloatArray(outputSize)

        var outIdx = 0
        for (i in 0 until waveformData.size step decimationFactor) {
            val end = min(i + decimationFactor, waveformData.size)
            var minVal = 1f
            var maxVal = -1f
            for (j in i until end) {
                val v = waveformData[j]
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
            decimatedPeaks[outIdx] = minVal
            decimatedPeaks[outIdx + 1] = maxVal
            outIdx += 2
        }
    }

    /** Get visible time range in seconds */
    private fun getVisibleTimeRange(): Pair<Double, Double> {
        val duration = waveformData.size.toDouble() / sampleRate
        val visibleDuration = duration / scaleX
        val startTime = (translateX / width) * duration
        val endTime = startTime + visibleDuration
        return startTime to endTime
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2 + verticalOffset

        // Background
        canvas.drawColor(0xFF0F0A18.toInt())

        // Grid
        drawGrid(canvas, width, height)

        // Waveform
        if (waveformData.isNotEmpty()) {
            drawWaveform(canvas, width, height, centerY)
        }

        // Time axis
        drawTimeAxis(canvas, width, height)

        // dB scale (Y axis)
        drawDbScale(canvas, width, height, centerY)

        // Selection
        if (selectionStart >= 0 && selectionEnd >= 0) {
            val left = min(selectionStart, selectionEnd)
            val right = max(selectionStart, selectionEnd)
            paintSelection.color = 0x4400D4AA.toInt()
            canvas.drawRect(left, 0f, right, height, paintSelection)
        }

        // Playback cursor
        if (cursorPosition >= 0) {
            val cursorX = (cursorPosition / waveformData.size) * width * scaleX + translateX
            if (cursorX >= 0 && cursorX <= width) {
                canvas.drawLine(cursorX, 0f, cursorX, height, paintCursor)
            }
        }
    }

    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        val hLines = 4
        for (i in 1 until hLines) {
            val y = height * i / hLines
            canvas.drawLine(0f, y, width, y, paintGrid)
        }
        val vLines = 10
        for (i in 1 until vLines) {
            val x = width * i / vLines
            canvas.drawLine(x, 0f, x, height, paintGrid)
        }
    }

    private fun drawWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        val scale = (height / 2 - 20) * verticalZoom
        val halfH = height / 2 + verticalOffset

        if (decimatedPeaks.isNotEmpty() && scaleX > 0.5f) {
            // Use decimated data for zoomed out view
            drawDecimatedWaveform(canvas, width, height, halfH, scale)
        } else {
            // Full resolution for zoomed in
            drawFullWaveform(canvas, width, height, halfH, scale)
        }
    }

    private fun drawDecimatedWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float, scale: Float) {
        val points = decimatedPeaks.size / 2
        val pxPerPoint = width / points * scaleX

        val path = Path()
        val fillPath = Path()
        var first = true

        for (i in 0 until points) {
            val x = i * pxPerPoint + translateX
            if (x < -pxPerPoint || x > width + pxPerPoint) continue

            val minVal = decimatedPeaks[i * 2]
            val maxVal = decimatedPeaks[i * 2 + 1]

            val yMin = centerY - maxVal * scale
            val yMax = centerY - minVal * scale

            if (first) {
                path.moveTo(x, yMin)
                fillPath.moveTo(x, centerY)
                first = false
            } else {
                path.lineTo(x, yMin)
            }
            // Fill path follows bottom
            fillPath.lineTo(x, yMax)
        }

        // Close fill path
        if (!first) {
            val lastX = (points - 1) * pxPerPoint + translateX
            fillPath.lineTo(lastX, centerY)
            fillPath.close()
            canvas.drawPath(fillPath, paintWaveformFill)
        }
        canvas.drawPath(path, paintWaveform)
    }

    private fun drawFullWaveform(canvas: Canvas, width: Float, height: Float, centerY: Float, scale: Float) {
        val visibleSamples = (width / scaleX).toInt()
        val startSample = (-translateX / scaleX * waveformData.size / width).toInt().coerceIn(0, waveformData.size - 1)
        val endSample = min(startSample + visibleSamples + 10, waveformData.size)

        val path = Path()
        val fillPath = Path()
        var first = true

        for (i in startSample until endSample) {
            val x = (i.toFloat() / waveformData.size) * width * scaleX + translateX
            val y = centerY - waveformData[i] * scale

            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, centerY)
                first = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        if (!first) {
            val lastX = (endSample.toFloat() / waveformData.size) * width * scaleX + translateX
            fillPath.lineTo(lastX, centerY)
            fillPath.close()
            canvas.drawPath(fillPath, paintWaveformFill)
        }
        canvas.drawPath(path, paintWaveform)
    }

    private fun drawTimeAxis(canvas: Canvas, width: Float, height: Float) {
        val (startTime, endTime) = getVisibleTimeRange()
        val duration = endTime - startTime

        val labels = 8
        for (i in 0 until labels) {
            val x = width * i / (labels - 1)
            val time = startTime + duration * i / (labels - 1)
            val label = formatTime(time)
            canvas.drawText(label, x, height - 4f, paintAxis)
        }
    }

    private fun drawDbScale(canvas: Canvas, width: Float, height: Float, centerY: Float) {
        val dbLevels = intArrayOf(0, -6, -12, -24, -48, -96)
        for (db in dbLevels) {
            val ratio = if (db == -96) 0f else Math.pow(10.0, db / 20.0).toFloat()
            val y = centerY - ratio * (height / 2 - 20) * verticalZoom + verticalOffset
            val label = if (db == -96) "-∞" else "${db}dB"
            canvas.drawText(label, 4f, y + 7f, paintAxis)
            canvas.drawLine(0f, y, 30f, y, paintAxis)
        }
    }

    private fun formatTime(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        val ms = ((seconds * 1000) % 1000).toInt()
        return if (mins > 0) String.format("%d:%02d.%03d", mins, secs, ms)
        else String.format("%d.%03d", secs, ms)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isSelecting = true
                selectionStart = event.x
                selectionEnd = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    // Horizontal zoom
                    val focusX = scaleDetector.focusX
                    val newScale = (scaleX * scaleDetector.scaleFactor).coerceIn(0.1f, 100f)
                    translateX -= (focusX - width / 2f) * (newScale / scaleX - 1)
                    scaleX = newScale
                } else {
                    val dx = event.x - lastTouchX
                    if (abs(dx) > 5) {
                        translateX += dx
                    }
                    selectionEnd = event.x
                }
                lastTouchX = event.x
                postInvalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSelecting = false
                if (abs(selectionEnd - selectionStart) < 20) {
                    selectionStart = -1f
                    selectionEnd = -1f
                }
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean = true
    }

    /** Get selection time range in seconds */
    fun getSelectionTimeRange(): Pair<Double, Double>? {
        if (selectionStart < 0 || selectionEnd < 0) return null
        val (startTime, endTime) = getVisibleTimeRange()
        val duration = endTime - startTime
        val left = min(selectionStart, selectionEnd)
        val right = max(selectionStart, selectionEnd)
        val start = startTime + (left / width) * duration
        val end = startTime + (right / width) * duration
        return start to end
    }

    /** Clear selection */
    fun clearSelection() {
        selectionStart = -1f
        selectionEnd = -1f
        postInvalidate()
    }

    /** Zoom to fit entire waveform */
    fun zoomToFit() {
        scaleX = 1f
        translateX = 0f
        postInvalidate()
    }

    /** Zoom to selection */
    fun zoomToSelection() {
        val range = getSelectionTimeRange() ?: return
        val duration = waveformData.size.toDouble() / sampleRate
        scaleX = (duration / (range.second - range.first)).toFloat().coerceIn(0.1f, 100f)
        translateX = -(range.first / duration * width * scaleX).toFloat()
        postInvalidate()
    }
}