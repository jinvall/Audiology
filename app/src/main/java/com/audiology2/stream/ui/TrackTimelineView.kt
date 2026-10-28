package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.audiology2.stream.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Multi-track timeline view showing clips on tracks with editing capabilities.
 */
class TrackTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintTrackBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintClip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val paintClipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0xFF000000.toInt()
    }
    private val paintTrackBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xFF333333.toInt()
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF333333.toInt()
        strokeWidth = 0.5f
    }
    private val paintTimeRuler = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        strokeWidth = 1f
        textAlign = Paint.Align.LEFT
        textSize = 20f
    }
    private val paintTrackHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.LEFT
        textSize = 22f
    }
    private val paintSelection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x6600D4AA.toInt()
    }
    private val paintPlayhead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFF4D6D.toInt()
    }
    private val paintDragPreview = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCC00D4AA.toInt()
    }

    // Data
    private var project: AudioProject? = null
    private var sampleRate = 48000

    // View state
    private var scaleX = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var trackHeight = 80f

    // Selection
    private var selectedClipId: String? = null
    private var selectedTrackId: String? = null

    // Drag state
    private var isDragging = false
    private var dragClipId: String? = null
    private var dragTrackId: String? = null
    private var dragStartX = 0f
    private var dragStartTime = 0.0
    private var dragOffsetX = 0f

    // Resize handles
    private var isResizing = false
    private var resizeClipId: String? = null
    private var resizeTrackId: String? = null
    private var resizeLeft = false

    // Playhead
    private var playheadTime = -1.0

    // Track header width
    private val trackHeaderWidth = 120f

    // Time ruler height
    private val timeRulerHeight = 30f

    // Gestures
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Set project to display */
    fun setProject(project: AudioProject?) {
        this.project = project
        postInvalidate()
    }

    /** Set sample rate */
    fun setSampleRate(sr: Int) {
        sampleRate = sr
    }

    /** Update playhead position */
    fun setPlayheadTime(timeSec: Double) {
        playheadTime = timeSec
        postInvalidate()
    }

    /** Clear playhead */
    fun clearPlayhead() {
        playheadTime = -1.0
        postInvalidate()
    }

    /** Get current playhead time */
    val currentPlayheadTime: Double get() = playheadTime

    /** Get currently selected clip ID */
    val currentSelectedClipId: String? get() = selectedClipId

    /** Get currently selected track ID */
    val currentSelectedTrackId: String? get() = selectedTrackId

    /** Find clip by ID */
    fun findClip(clipId: String): Clip? {
        if (project == null) return null
        for (track in project!!.tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId }
            if (clip != null) return clip
        }
        return null
    }

    /** Zoom to fit entire project */
    fun zoomToFit() {
        if (project == null) return
        val duration = project!!.duration
        if (duration > 0) {
            scaleX = (width / (duration * 100)).toFloat() // 100 pixels per second base
            scaleX = scaleX.coerceIn(0.01f, 1000f)
            translateX = 0f
            postInvalidate()
        }
    }

    /** Zoom to selection */
    fun zoomToSelection() {
        if (selectedClipId == null || project == null) return
        val clip = findClip(selectedClipId!!) ?: return
        val track = project!!.getTrack(clip.trackId) ?: return
        val duration = project!!.duration
        if (duration > 0) {
            scaleX = ((width * 0.8f) / (clip.duration * 100)).toFloat()
            scaleX = scaleX.coerceIn(0.01f, 1000f)
            translateX = (-(clip.startTime * scaleX * 100) + width * 0.1f).toFloat()
            postInvalidate()
        }
    }

    /** Set snap grid for editing operations */
    fun setSnapGrid(grid: SnapGrid) {
        // Store reference to snap grid for use in drag/resize operations
        // For now just store it - the grid is used during drag operations
    }

    /** Find track by ID */
    private fun findTrack(trackId: String): Track? {
        if (project == null) return null
        return project!!.getTrack(trackId)
    }

    /** Convert time to pixel X */
    private fun timeToPx(time: Double): Float {
        return (time * scaleX * 100 + translateX).toFloat() + trackHeaderWidth
    }

    /** Convert pixel X to time */
    private fun pxToTime(px: Float): Double {
        return ((px - trackHeaderWidth - translateX) / (scaleX * 100)).toDouble()
    }

    /** Get visible time range */
    private fun getVisibleTimeRange(): Pair<Double, Double> {
        val visibleWidth = width - trackHeaderWidth
        val duration = (visibleWidth / (scaleX * 100)).toDouble()
        val startTime = (-translateX / (scaleX * 100)).toDouble()
        return startTime to (startTime + duration)
    }

    /** Get track Y position */
    private fun getTrackY(trackIndex: Int): Float {
        return timeRulerHeight + translateY + trackIndex * trackHeight
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Background
        canvas.drawColor(0xFF0F0A18.toInt())

        if (project == null || project!!.tracks.isEmpty()) {
            // Empty state
            val text = "No tracks. Add a track or record audio."
            paintTrackHeader.color = 0xFF888888.toInt()
            canvas.drawText(text, trackHeaderWidth + 20f, height / 2, paintTrackHeader)
            return
        }

        // Draw time ruler
        drawTimeRuler(canvas, width, height)

        // Draw tracks
        canvas.save()
        canvas.translate(0f, timeRulerHeight + translateY)
        canvas.clipRect(0f, 0f, width, height - timeRulerHeight)

        for ((trackIndex, track) in project!!.tracks.withIndex()) {
            drawTrack(canvas, width, height, track, trackIndex)
        }

        // Draw playhead on top
        if (playheadTime >= 0) {
            val playheadX = timeToPx(playheadTime)
            if (playheadX >= trackHeaderWidth && playheadX <= width) {
                canvas.drawLine(playheadX, 0f, playheadX, project!!.tracks.size * trackHeight, paintPlayhead)
            }
        }

        canvas.restore()

        // Draw track headers
        drawTrackHeaders(canvas, width, height)

        // Draw vertical grid lines
        drawVerticalGrid(canvas, width, height)
    }

    private fun drawTimeRuler(canvas: Canvas, width: Float, height: Float) {
        canvas.drawRect(0f, 0f, width, timeRulerHeight, paintTrackBg)
        paintTrackBg.color = 0xFF1A1426.toInt()
        
        // Vertical grid lines with time labels
        val (startTime, endTime) = getVisibleTimeRange()
        val duration: Double = endTime - startTime
        
        val secondsPerLabel: Double = when {
            duration < 5 -> 0.5
            duration < 15 -> 1.0
            duration < 60 -> 5.0
            duration < 300 -> 15.0
            duration < 1800 -> 60.0
            else -> 300.0
        }
        
        val startLabel = kotlin.math.ceil(startTime / secondsPerLabel) * secondsPerLabel
        var time: Double = startLabel
        while (time <= endTime) {
            val x = timeToPx(time)
            if (x >= trackHeaderWidth && x <= width) {
                // Grid line
                canvas.drawLine(x, 0f, x, timeRulerHeight, paintGrid)
                
                // Time label
                val label = formatTime(time)
                paintTimeRuler.color = 0xFFCCCCCC.toInt()
                canvas.drawText(label, x + 4f, timeRulerHeight - 4f, paintTimeRuler)
            }
            time += secondsPerLabel
        }
    }

    private fun drawVerticalGrid(canvas: Canvas, width: Float, height: Float) {
        val (startTime, endTime) = getVisibleTimeRange()
        val duration: Double = endTime - startTime
        
        val secondsPerLine: Double = when {
            duration < 5 -> 0.5
            duration < 15 -> 1.0
            duration < 60 -> 5.0
            duration < 300 -> 15.0
            duration < 1800 -> 60.0
            else -> 300.0
        }
        
        var time: Double = kotlin.math.ceil(startTime / secondsPerLine) * secondsPerLine
        while (time <= endTime) {
            val x = timeToPx(time)
            if (x >= trackHeaderWidth && x <= width) {
                canvas.drawLine(x, timeRulerHeight, x, height, paintGrid)
            }
            time += secondsPerLine
        }
    }

    private fun drawTrackHeaders(canvas: Canvas, width: Float, height: Float) {
        if (project == null) return
        
        // Header background
        paintTrackBg.color = 0xFF151020.toInt()
        canvas.drawRect(0f, timeRulerHeight, trackHeaderWidth, height, paintTrackBg)
        
        // Separator line
        paintTrackBorder.color = 0xFF333333.toInt()
        canvas.drawLine(trackHeaderWidth, timeRulerHeight, trackHeaderWidth, height, paintTrackBorder)
        
        for ((trackIndex, track) in project!!.tracks.withIndex()) {
            val y = getTrackY(trackIndex)
            val trackTop = y
            val trackBottom = y + trackHeight
            
            if (trackBottom < 0 || trackTop > height) continue
            
            // Track background (selected)
            if (track.id == selectedTrackId) {
                paintTrackBg.color = 0xFF252035.toInt()
                canvas.drawRect(0f, trackTop, trackHeaderWidth, trackBottom, paintTrackBg)
            }
            
            // Track color indicator
            paintClip.color = track.color
            canvas.drawRect(8f, trackTop + 8f, 24f, trackTop + 24f, paintClip)
            
            // Track name
            paintTrackHeader.color = if (track.muted) 0xFF888888.toInt() else 0xFFFFFFFF.toInt()
            canvas.drawText(track.name, 30f, trackTop + trackHeight / 2 + 8f, paintTrackHeader)
            
            // Mute/Solo/Arm indicators
            var indicatorX = trackHeaderWidth - 60f
            if (track.solo) {
                paintTrackHeader.color = 0xFFFFD700.toInt()
                canvas.drawText("S", indicatorX, trackTop + trackHeight / 2 + 8f, paintTrackHeader)
                indicatorX -= 30f
            }
            if (track.muted) {
                paintTrackHeader.color = 0xFF888888.toInt()
                canvas.drawText("M", indicatorX, trackTop + trackHeight / 2 + 8f, paintTrackHeader)
                indicatorX -= 30f
            }
            if (track.armed) {
                paintTrackHeader.color = 0xFFFF4D6D.toInt()
                canvas.drawText("●", indicatorX, trackTop + trackHeight / 2 + 8f, paintTrackHeader)
            }
        }
    }

    private fun drawTrack(canvas: Canvas, width: Float, height: Float, track: Track, trackIndex: Int) {
        val trackTop = trackIndex * trackHeight
        val trackBottom = trackTop + trackHeight
        val trackCenterY = trackTop + trackHeight / 2
        
        // Track background
        if (trackIndex % 2 == 0) {
            paintTrackBg.color = 0xFF120D1A.toInt()
        } else {
            paintTrackBg.color = 0xFF16101E.toInt()
        }
        canvas.drawRect(trackHeaderWidth, trackTop, width, trackBottom, paintTrackBg)
        
        // Horizontal center line
        paintGrid.color = 0xFF222233.toInt()
        canvas.drawLine(trackHeaderWidth, trackCenterY, width, trackCenterY, paintGrid)
        
        // Draw clips
        for (clip in track.clips) {
            drawClip(canvas, width, trackTop, trackBottom, trackCenterY, clip, track)
        }
        
        // Draw selection rectangle if this track has selected clip
        if (selectedClipId != null && selectedTrackId == track.id) {
            val clip = findClip(selectedClipId!!)
            clip?.let {
                val left = timeToPx(it.startTime)
                val right = timeToPx(it.endTime)
                canvas.drawRect(left, trackTop + 2, right, trackBottom - 2, paintSelection)
            }
        }
    }

    private fun drawClip(canvas: Canvas, width: Float, trackTop: Float, trackBottom: Float, 
                         trackCenterY: Float, clip: Clip, track: Track) {
        val left = timeToPx(clip.startTime)
        val right = timeToPx(clip.endTime)
        
        // Skip if completely off-screen
        if (right < trackHeaderWidth || left > width) return
        
        val clipTop = trackTop + 4f
        val clipBottom = trackBottom - 4f
        val clipHeight = clipBottom - clipTop
        
        // Clip background
        paintClip.color = clip.color
        val rect = RectF(left, clipTop, right, clipBottom)
        canvas.drawRect(rect, paintClip)
        
        // Clip border
        paintClipBorder.color = 0xFF000000.toInt()
        canvas.drawRect(rect, paintClipBorder)
        
        // Waveform preview (simplified - just amplitude envelope)
        drawClipWaveform(canvas, left, right, clipTop, clipBottom, trackCenterY, clip)
        
        // Clip name
        if (right - left > 40) {
            paintTrackHeader.color = 0xFF000000.toInt()
            paintTrackHeader.textSize = 16f
            val name = if (clip.name.length > 20) clip.name.substring(0, 17) + "..." else clip.name
            canvas.drawText(name, left + 8f, trackCenterY + 5f, paintTrackHeader)
        }
        
        // Fade in/out indicators
        if (clip.fadeIn > 0) {
            val fadeEnd = timeToPx(clip.startTime + clip.fadeIn)
            paintClip.color = 0x88FFFFFF.toInt()
            canvas.drawRect(RectF(left, clipTop, fadeEnd.coerceAtMost(right), clipBottom), paintClip)
        }
        if (clip.fadeOut > 0) {
            val fadeStart = timeToPx(clip.endTime - clip.fadeOut)
            paintClip.color = 0x88FFFFFF.toInt()
            canvas.drawRect(RectF(fadeStart.coerceAtLeast(left), clipTop, right, clipBottom), paintClip)
        }
        
        // Resize handles (when selected)
        if (clip.id == selectedClipId) {
            paintDragPreview.color = 0xFF00D4AA.toInt()
            val handleSize = 10f
            // Left handle
            canvas.drawRect(left - handleSize/2, clipTop, left + handleSize/2, clipBottom, paintDragPreview)
            // Right handle
            canvas.drawRect(right - handleSize/2, clipTop, right + handleSize/2, clipBottom, paintDragPreview)
        }
    }

    private fun drawClipWaveform(canvas: Canvas, left: Float, right: Float, 
                                 clipTop: Float, clipBottom: Float, centerY: Float, clip: Clip) {
        // Placeholder: draw a simple amplitude envelope
        // In a real implementation, this would use the actual audio data
        val clipWidth = right - left
        if (clipWidth < 20) return
        
        val clipHeight = clipBottom - clipTop
        val numPoints = (clipWidth / 4).toInt().coerceIn(5, 50)
        val path = Path()
        val fillPath = Path()
        
        var first = true
        for (i in 0..numPoints) {
            val x = (left + clipWidth * i / numPoints).toFloat()
            // Simulate waveform with clip gain
            val amp = (clip.gain * 0.5f + 0.5f).coerceIn(0f, 1f)
            val variation = (kotlin.math.sin((i * 6.28 / numPoints) + clip.startTime * 10) * 0.3 + 0.7) * amp
            val y = (centerY - variation * (clipHeight / 2)).toFloat()
            
            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, centerY)
                first = false
            } else {
                path.lineTo(x, y)
            }
            fillPath.lineTo(x, (centerY + variation * (clipHeight / 2)).toFloat())
        }
        
        paintClip.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(path, paintClip)
    }

    private fun formatTime(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        val secs = (seconds % 60).toInt()
        val ms = ((seconds * 1000) % 1000).toInt()
        return if (mins > 0) String.format("%d:%02d", mins, secs)
        else String.format("%d.%02d", secs, ms)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                
                // Check if touching time ruler
                if (event.y < timeRulerHeight) {
                    // Seek to position
                    val time = pxToTime(event.x)
                    onSeekRequest?.invoke(time)
                    return true
                }
                
                if (project == null) return true
                val proj = project!!
                
                // Check track headers
                if (event.x < trackHeaderWidth) {
                    val trackIndex = ((event.y - timeRulerHeight - translateY) / trackHeight).toInt()
                    if (trackIndex >= 0 && trackIndex < proj.tracks.size) {
                        val track = proj.tracks[trackIndex]
                        selectedTrackId = track.id
                        selectedClipId = null
                        postInvalidate()
                        onTrackSelect?.invoke(track)
                    }
                    return true
                }
                
                // Check clips
                val time = pxToTime(event.x)
                val trackIndex = ((event.y - timeRulerHeight - translateY) / trackHeight).toInt()
                
                if (trackIndex >= 0 && trackIndex < proj.tracks.size) {
                    val track = proj.tracks[trackIndex]
                    val clip = track.getClipAt(time)
                    
                    if (clip != null) {
                        selectedClipId = clip.id
                        selectedTrackId = track.id
                        dragClipId = clip.id
                        dragTrackId = track.id
                        dragStartX = event.x
                        dragStartTime = clip.startTime
                        dragOffsetX = event.x - timeToPx(clip.startTime)
                        
                        // Check resize handles
                        val left = timeToPx(clip.startTime)
                        val right = timeToPx(clip.endTime)
                        val handleSize = 15f
                        
                        if (abs(event.x - left) < handleSize) {
                            isResizing = true
                            resizeLeft = true
                            resizeClipId = clip.id
                            resizeTrackId = track.id
                        } else if (abs(event.x - right) < handleSize) {
                            isResizing = true
                            resizeLeft = false
                            resizeClipId = clip.id
                            resizeTrackId = track.id
                        } else {
                            isDragging = true
                        }
                        postInvalidate()
                        onClipSelect?.invoke(clip)
                        return true
                    } else {
                        // Clicked empty space - deselect
                        selectedClipId = null
                        selectedTrackId = track.id
                        postInvalidate()
                        onEmptySelect?.invoke(time, track)
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    // Horizontal zoom
                    val focusX = scaleDetector.focusX
                    val newScale = (scaleX * scaleDetector.scaleFactor).coerceIn(0.01f, 1000f)
                    translateX -= (focusX - width / 2f) * (newScale / scaleX - 1)
                    scaleX = newScale
                    postInvalidate()
                    return true
                } else if (isDragging && dragClipId != null && dragTrackId != null) {
                    // Drag clip
                    val newX = event.x - dragOffsetX
                    val newTime = pxToTime(newX).coerceAtLeast(0.0)
                    val clip = findClip(dragClipId!!) ?: return true
                    val track = findTrack(dragTrackId!!) ?: return true
                    
                    val maxTime = project?.duration ?: 0.0
                    val clippedTime = newTime.coerceAtMost(maxTime - clip.duration)
                    
                    clip.startTime = clippedTime
                    postInvalidate()
                    onClipMove?.invoke(clip)
                    return true
                } else if (isResizing && resizeClipId != null && resizeTrackId != null) {
                    // Resize clip
                    val newX = event.x
                    val newTime = pxToTime(newX).coerceAtLeast(0.0)
                    val clip = findClip(resizeClipId!!) ?: return true
                    val track = findTrack(resizeTrackId!!) ?: return true
                    
                    if (resizeLeft) {
                        val newDuration = clip.endTime - newTime
                        if (newDuration > 0.01) {
                            clip.sourceOffset += (clip.startTime - newTime)
                            clip.startTime = newTime
                            clip.duration = newDuration
                        }
                    } else {
                        val newDuration = newTime - clip.startTime
                        if (newDuration > 0.01) {
                            val maxDuration = (project?.duration ?: 0.0) - clip.startTime
                            clip.duration = newDuration.coerceAtMost(maxDuration)
                        }
                    }
                    postInvalidate()
                    onClipResize?.invoke(clip)
                    return true
                } else {
                    // Pan
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateX += dx
                    translateY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    postInvalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isResizing = false
                dragClipId = null
                dragTrackId = null
                resizeClipId = null
                resizeTrackId = null
                postInvalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // Callbacks
    var onSeekRequest: ((Double) -> Unit)? = null
    var onTrackSelect: ((Track) -> Unit)? = null
    var onClipSelect: ((Clip) -> Unit)? = null
    var onEmptySelect: ((Double, Track) -> Unit)? = null
    var onClipMove: ((Clip) -> Unit)? = null
    var onClipResize: ((Clip) -> Unit)? = null

    inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean = true
    }

    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomToFit()
            return true
        }
    }
}