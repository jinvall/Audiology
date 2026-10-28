package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Piano-roll / step-sequencer editor for a [MidiClip].
 *
 * Layout: time flows left→right (ticks, grid snapped to the [SnapGrid]
 * subdivision), pitch flows top→bottom (row per semitone). Tap places a
 * note, drag moves/transposes it, the right edge resizes it, long-press
 * requests velocity editing, and the left ribbon auditions pitches.
 * Mutations are reported through [onClipChanged] so the host can persist.
 */
class PianoRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- State ----
    private var clip: MidiClip? = null
    private var bpm: Double = 120.0
    private var isDrumMode = false
    private var drumRootPitch = 60
    private var drumRowCount = 16

    private var pxPerTick = 1.2f
    private var scrollTick = 0L
    private var lowPitch = 48
    private var highPitch = 84

    private var selectedNoteId: String? = null

    private enum class DragMode { NONE, PLACE, MOVE, RESIZE }
    private var dragMode = DragMode.NONE
    private var dragNoteId: String? = null
    private var dragStartX = 0f
    private var dragStartTick = 0L
    private var dragStartPitch = 60
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointers = 0

    // ---- Geometry ----
    private val ribbonWidth = 44f * resources.displayMetrics.density
    private val rulerHeight = 26f * resources.displayMetrics.density
    private val rowHeight = 24f * resources.displayMetrics.density

    // ---- Paint ----
    private val paintWhiteKey = Paint().apply { color = 0xFFF7F7F9.toInt() }
    private val paintBlackKey = Paint().apply { color = 0xFFB9BAC4.toInt() }
    private val paintBeatLine = Paint().apply { color = 0xFF2A2A35.toInt(); strokeWidth = 1.5f }
    private val paintSubLine = Paint().apply { color = 0xFF232330.toInt(); strokeWidth = 1f }
    private val paintMeasureLine = Paint().apply { color = 0xFF3C3C4C.toInt(); strokeWidth = 2f }
    private val paintRulerBg = Paint().apply { color = 0xFF16161E.toInt() }
    private val paintRulerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val paintKeyLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A98.toInt(); textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private val paintNote = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00D4AA.toInt() }
    private val paintNoteSel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt() }
    private val paintNoteBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x66000000.toInt()
    }
    private val paintPlayhead = Paint().apply { color = 0xFFFF5252.toInt(); strokeWidth = 2f }

    // ---- Callbacks ----
    /** Fired after any note change; pass the updated clip to persist. */
    var onClipChanged: ((MidiClip) -> Unit)? = null
    /** Request velocity editing for a note (host shows a dialog). */
    var onVelocityRequest: ((MidiNote) -> Unit)? = null
    /** Audit a pitch (host can play a test tone / sample). */
    var onPitchAudition: ((Int) -> Unit)? = null

    // ---- Gestures ----
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // ---- Public API ----

    fun setClip(clip: MidiClip?, bpm: Double) {
        this.clip = clip
        this.bpm = bpm.coerceIn(40.0, 300.0)
        selectedNoteId = null
        if (clip != null) {
            isDrumMode = clip.drumMode
            drumRootPitch = clip.drumRootPitch
            if (isDrumMode) {
                lowPitch = drumRootPitch
                highPitch = drumRootPitch + drumRowCount - 1
            } else {
                fitPitchRange()
            }
        }
        postInvalidate()
    }

    fun setDrumMode(drum: Boolean) {
        isDrumMode = drum
        clip?.let { c ->
            if (isDrumMode) {
                lowPitch = c.drumRootPitch
                highPitch = c.drumRootPitch + drumRowCount - 1
            } else {
                fitPitchRange()
            }
        }
        postInvalidate()
    }

    val isDrumModeActive: Boolean get() = isDrumMode
    val drumRoot: Int get() = drumRootPitch
    val drumRows: Int get() = drumRowCount

    /** Current tempo used for tick/time conversion. */
    fun setTempo(bpm: Double) { this.bpm = bpm.coerceIn(40.0, 300.0); postInvalidate() }

    val selectedNote: MidiNote?
        get() = clip?.notes?.firstOrNull { it.id == selectedNoteId }

    fun deleteSelectedNote() {
        val c = clip ?: return
        val id = selectedNoteId ?: return
        val updated = c.withNotes(c.notes.filterNot { it.id == id })
        setClip(updated, bpm)
        onClipChanged?.invoke(updated)
    }

    /** Replace a note (velocity updates etc). */
    fun updateNote(note: MidiNote) {
        val c = clip ?: return
        val updated = c.withNotes(c.notes.map { if (it.id == note.id) note else it })
        setClip(updated, bpm)
        onClipChanged?.invoke(updated)
    }

    /** Zoom so [beats] beats are visible across the roll. */
    fun zoomToFitBeats(beats: Int = 4) {
        val c = clip ?: return
        if (width <= 0) return
        val beatTicks = c.ticksPerBeat.toFloat()
        val visibleArea = width - ribbonWidth - 8f
        pxPerTick = max(0.05f, (visibleArea / (beats * beatTicks)).coerceAtMost(3f))
        val end = max(c.endTick, beats * beatTicks.toLong())
        centerOnTick(end / 2)
    }

    private fun centerOnTick(tick: Long) {
        scrollTick = (tick - visibleTicks() / 2).coerceAtLeast(0L)
        postInvalidate()
    }

    private fun visibleTicks(): Long {
        if (width <= 0) return 480L
        return ((width - ribbonWidth) / pxPerTick).toLong().coerceAtLeast(120L)
    }

    // ---- Mapping ----

    private fun xForTick(tick: Long): Float = ribbonWidth + (tick - scrollTick) * pxPerTick
    private fun tickForX(x: Float): Long {
        val tick = scrollTick + ((x - ribbonWidth) / pxPerTick).toLong()
        return tick.coerceAtLeast(0L)
    }
    private fun rowForPitch(pitch: Int): Float = rulerHeight + (highPitch - pitch) * rowHeight
    private fun pitchForRow(y: Float): Int {
        val pitch = highPitch - (((y - rulerHeight) / rowHeight).toInt())
        return pitch.coerceIn(0, 127)
    }

    /** Current grid division in ticks (from SnapGrid mode or 16th default). */
    private fun stepTicks(c: MidiClip): Long {
        if (isDrumMode) return (c.ticksPerBeat / 4).toLong() // 16th steps
        // Piano roll default: one 16th note.
        return (c.ticksPerBeat / 4).toLong()
    }

    private fun snapTick(tick: Long): Long {
        val step = clip?.ticksPerBeat?.div(4)?.toLong() ?: 120L
        return (tick / step) * step
    }

    private fun fitPitchRange() {
        val c = clip ?: return
        val notes = c.notes
        if (notes.isEmpty()) {
            lowPitch = 48; highPitch = 84
            return
        }
        var lo = notes.minOf { it.pitch }
        var hi = notes.maxOf { it.pitch }
        lo = (lo - 4).coerceAtLeast(0)
        hi = (hi + 4).coerceAtMost(127)
        if (hi - lo < 12) hi = (lo + 12).coerceAtMost(127)
        lowPitch = lo; highPitch = hi
    }

    // ---- Touch ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointers = 1
                lastTouchX = event.x
                lastTouchY = event.y
                val c = clip ?: return true

                // Ribbon audition
                if (event.x < ribbonWidth && event.y > rulerHeight) {
                    onPitchAudition?.invoke(pitchForRow(event.y))
                    return true
                }

                val tick = snapTick(tickForX(event.x))
                val (note, region) = noteAt(event.x, event.y)
                when {
                    note != null && region == Region.RESIZE -> {
                        dragMode = DragMode.RESIZE
                        dragNoteId = note.id
                        selectedNoteId = note.id
                        postInvalidate()
                    }
                    note != null -> {
                        dragMode = DragMode.MOVE
                        dragNoteId = note.id
                        selectedNoteId = note.id
                        dragStartX = event.x
                        dragStartTick = note.startTick
                        dragStartPitch = note.pitch
                        postInvalidate()
                    }
                    else -> {
                        // Tap empty cell: place a note.
                        selectedNoteId = null
                        if (event.y > rulerHeight) {
                            placeNote(tick, pitchForRow(event.y))
                        }
                        postInvalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers++
                dragMode = DragMode.NONE
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers = (activePointers - 1).coerceAtLeast(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) {
                    val factor = scaleDetector.scaleFactor
                    val focusX = scaleDetector.focusX
                    val oldPx = pxPerTick
                    val newPx = (pxPerTick * factor).coerceIn(0.05f, 6f)
                    // Keep the timeline under the focus point stable.
                    val focusTick = tickForX(focusX)
                    pxPerTick = newPx
                    scrollTick = (focusTick - ((focusX - ribbonWidth) / newPx).toLong()).coerceAtLeast(0L)
                    postInvalidate()
                    return true
                }
                if (activePointers >= 2) {
                    val dx = event.x - lastTouchX
                    scrollTick = (scrollTick - (dx / pxPerTick).toLong()).coerceAtLeast(0L)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    postInvalidate()
                    return true
                }
                when (dragMode) {
                    DragMode.MOVE -> moveDrag(event)
                    DragMode.RESIZE -> resizeDrag(event)
                    else -> { /* scroll handled by fling detector */ }
                }
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                dragNoteId = null
                activePointers = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private enum class Region { BODY, RESIZE, NONE }
    private data class Hit(val note: MidiNote?, val region: Region)

    private fun noteAt(x: Float, y: Float): Hit {
        val c = clip ?: return Hit(null, Region.NONE)
        if (y < rulerHeight) return Hit(null, Region.NONE)
        val pitch = pitchForRow(y)
        val tick = tickForX(x)
        val handle = 14f * resources.displayMetrics.density
        for (n in c.notes.sortedByDescending { it.startTick }) {
            val bodyLeft = xForTick(n.startTick)
            val bodyRight = xForTick(n.endTick)
            if (n.pitch == pitch && tick >= n.startTick && tick <= n.endTick) {
                val region = if (x > bodyRight - handle) Region.RESIZE else Region.BODY
                return Hit(n, region)
            }
        }
        return Hit(null, Region.NONE)
    }

    private fun moveDrag(event: MotionEvent) {
        val c = clip ?: return
        val noteId = dragNoteId ?: return
        val note = c.notes.firstOrNull { it.id == noteId } ?: return
        val tick = snapTick(tickForX(event.x).coerceAtLeast(0L))
        val pitch = pitchForRow(event.y).coerceIn(0, 127)
        val updated = note.copy(startTick = tick.coerceAtLeast(0L), pitch = pitch)
        applyNote(updated)
    }

    private fun resizeDrag(event: MotionEvent) {
        val c = clip ?: return
        val noteId = dragNoteId ?: return
        val note = c.notes.firstOrNull { it.id == noteId } ?: return
        val minStep = stepTicks(c).coerceAtLeast(1L)
        val endTick = snapTick(tickForX(event.x).coerceAtLeast(0L))
        val dur = (endTick - note.startTick).coerceAtLeast(minStep)
        applyNote(note.copy(durationTicks = dur))
    }

    private fun applyNote(note: MidiNote) {
        val c = clip ?: return
        val updated = c.withNotes(c.notes.map { if (it.id == note.id) note else it })
        setClip(updated, bpm)
        postInvalidate()
        onClipChanged?.invoke(updated)
    }

    private fun placeNote(tick: Long, pitch: Int) {
        val c = clip ?: return
        val step = stepTicks(c)
        // Avoid stacking a duplicate on the exact same cell.
        if (c.notes.any { it.startTick == tick && it.pitch == pitch }) return
        val note = MidiNote(startTick = tick, durationTicks = step, pitch = pitch, velocity = 100)
        val updated = c.withNotes(c.notes + note)
        selectedNoteId = note.id
        setClip(updated, bpm)
        postInvalidate()
        onClipChanged?.invoke(updated)
    }

    /** Insert a note programmatically (e.g. spectral capture) and persist. */
    fun addNote(note: MidiNote) {
        val c = clip ?: return
        val updated = c.withNotes(c.notes + note)
        selectedNoteId = note.id
        setClip(updated, bpm)
        postInvalidate()
        onClipChanged?.invoke(updated)
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = clip ?: return
        drawRuler(canvas, c)
        drawKeys(canvas)
        drawGrid(canvas, c)
        drawNotes(canvas, c)
    }

    private fun drawRuler(canvas: Canvas, c: MidiClip) {
        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, paintRulerBg)
        val beatsPerMeasure = 4
        // Beat lines + labels in the ruler.
        var beat = 0
        val startTick = scrollTick.coerceAtLeast(0L)
        val endTick = scrollTick + visibleTicks()
        val beatTicks = c.ticksPerBeat.toLong()
        var tick = (startTick / beatTicks) * beatTicks
        while (tick <= endTick) {
            val x = xForTick(tick)
            if (x >= ribbonWidth && x <= width) {
                val isMeasure = ((tick / beatTicks) % beatsPerMeasure) == 0L
                val label = (tick / beatTicks).toString()
                val paint = if (isMeasure) paintMeasureLine else paintBeatLine
                canvas.drawLine(x, rulerHeight - 10f, x, rulerHeight, paint)
                canvas.drawText(label, x + 3f, rulerHeight - 14f, paintRulerText)
            }
            tick += beatTicks
        }
        drawPlayhead(canvas)
    }

    private fun drawPlayhead(canvas: Canvas) {
        // No transport playhead for now; reserved.
    }

    private fun drawKeys(canvas: Canvas) {
        val top = rulerHeight
        val bottom = rulerHeight + (highPitch - lowPitch + 1) * rowHeight
        canvas.drawRect(0f, top, ribbonWidth, bottom, paintWhiteKey)
        for (p in lowPitch..highPitch) {
            val isBlack = isBlackKey(p)
            val y = rowForPitch(p)
            if (isBlack) {
                canvas.drawRect(ribbonWidth - 20f * resources.displayMetrics.density, y,
                    ribbonWidth, y + rowHeight, paintBlackKey)
            }
            if (p % 12 == 0) { // C notes
                canvas.drawText(cName(p), 3f, y + rowHeight - 5f, paintKeyLabel)
            } else {
                canvas.drawText(p.toString(), 3f, y + rowHeight - 5f, paintKeyLabel)
            }
        }
        canvas.drawRect(0f, top, ribbonWidth, bottom, paintNoteBorder)
    }

    private fun drawGrid(canvas: Canvas, c: MidiClip) {
        val startTick = scrollTick.coerceAtLeast(0L)
        val endTick = scrollTick + visibleTicks()
        val beatTicks = c.ticksPerBeat.toLong()
        canvas.drawRect(ribbonWidth, rulerHeight, width.toFloat(),
            rulerHeight + (highPitch - lowPitch + 1) * rowHeight, paintWhiteKey)
        // Row separation lines
        for (p in lowPitch..highPitch) {
            val y = rowForPitch(p) + rowHeight
            canvas.drawLine(ribbonWidth, y, width.toFloat(), y, paintSubLine)
        }
        // Beat/measure vertical lines
        var tick = (startTick / beatTicks) * beatTicks
        while (tick <= endTick) {
            val x = xForTick(tick)
            if (x >= ribbonWidth && x <= width) {
                val isMeasure = ((tick / beatTicks) % 4) == 0L
                canvas.drawLine(x, rulerHeight, x,
                    rulerHeight + (highPitch - lowPitch + 1) * rowHeight,
                    if (isMeasure) paintMeasureLine else paintBeatLine)
            }
            tick += beatTicks
        }
    }

    private fun drawNotes(canvas: Canvas, c: MidiClip) {
        for (n in c.notes) {
            val left = xForTick(n.startTick)
            val right = xForTick(n.endTick)
            if (right < ribbonWidth || left > width) continue
            val top = rowForPitch(n.pitch) + 2f
            val bottom = rowForPitch(n.pitch) + rowHeight - 2f
            if (top < rulerHeight) continue
            val rect = RectF(left + 1f, top, right - 1f, bottom)
            if (n.id == selectedNoteId) {
                canvas.drawRect(rect, paintNoteSel)
            } else {
                canvas.drawRect(rect, paintNote)
            }
            canvas.drawRect(rect, paintNoteBorder)
        }
    }

    private fun isBlackKey(pitch: Int): Boolean {
        val pc = pitch % 12
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10
    }

    private fun cName(pitch: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (pitch / 12) - 1
        return names[pitch % 12] + octave
    }

    // ---- Gesture listeners ----

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean = true
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onLongPress(e: MotionEvent) {
            val c = clip ?: return
            if (e.x < ribbonWidth || e.y < rulerHeight) return
            val (note, _) = noteAt(e.x, e.y)
            if (note != null) {
                selectedNoteId = note.id
                postInvalidate()
                onVelocityRequest?.invoke(note)
            }
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Tap on the ruler: centered scroll? Keep simple - no-op.
            return true
        }
    }
}
