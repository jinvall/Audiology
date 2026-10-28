package com.audiology2.stream.ui

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Grid snapping for timeline - beats, measures, seconds, frames.
 */
class SnapGrid(
    initialSampleRate: Int = 48000,
    initialTempo: Double = 120.0, // BPM
    initialTimeSignatureNumerator: Int = 4,
    initialTimeSignatureDenominator: Int = 4
) {

    /** Snap mode */
    enum class Mode {
        OFF,           // No snapping
        SECONDS,       // Snap to second boundaries
        FRAMES,        // Snap to audio frames (1/sampleRate)
        BEATS,         // Snap to beats (quarter notes)
        MEASURES,      // Snap to measure boundaries
        BEATS_8TH,     // Snap to 8th notes
        BEATS_16TH,    // Snap to 16th notes
        BEATS_TRIPLET  // Snap to triplets
    }

    var mode: Mode = Mode.OFF
        set(value) {
            field = value
        }

    var tempo: Double = initialTempo
        set(value) {
            field = value.coerceIn(30.0, 300.0)
            beatDuration = 60.0 / value
        }

    var timeSignatureNumerator: Int = initialTimeSignatureNumerator
        set(value) {
            field = value.coerceIn(1, 16)
            measureDuration = beatDuration * value
        }

    var timeSignatureDenominator: Int = initialTimeSignatureDenominator
        set(value) {
            field = value
        }

    var sampleRate: Int = initialSampleRate
        set(value) {
            field = value
            frameDuration = 1.0 / value
        }

    // Computed values
    private var beatDuration = 60.0 / initialTempo
    private var measureDuration = beatDuration * initialTimeSignatureNumerator
    private var frameDuration = 1.0 / initialSampleRate

    /** Snap time to nearest grid line based on mode */
    fun snap(time: Double): Double {
        return when (mode) {
            Mode.OFF -> time
            Mode.SECONDS -> time.roundToInt().toDouble()
            Mode.FRAMES -> (time / frameDuration).roundToLong() * frameDuration
            Mode.BEATS -> (kotlin.math.round(time / beatDuration)) * beatDuration
            Mode.MEASURES -> (kotlin.math.round(time / measureDuration)) * measureDuration
            Mode.BEATS_8TH -> (kotlin.math.round(time / (beatDuration / 2))) * (beatDuration / 2)
            Mode.BEATS_16TH -> (kotlin.math.round(time / (beatDuration / 4))) * (beatDuration / 4)
            Mode.BEATS_TRIPLET -> (kotlin.math.round(time / (beatDuration / 3))) * (beatDuration / 3)
        }
    }

    /** Snap time range (start and end) */
    fun snapRange(start: Double, end: Double): Pair<Double, Double> {
        return snap(start) to snap(end)
    }

    /** Get grid lines in visible range for drawing */
    fun getGridLines(startTime: Double, endTime: Double): List<GridLine> {
        val lines = mutableListOf<GridLine>()
        
        when (mode) {
            Mode.OFF -> {}
            Mode.SECONDS -> {
                val startSec = kotlin.math.ceil(startTime).toInt()
                val endSec = kotlin.math.floor(endTime).toInt()
                for (s in startSec..endSec) {
                    lines.add(GridLine(s.toDouble(), GridLine.Type.SECOND, "$s"))
                }
            }
            Mode.FRAMES -> {
                val startFrame = (kotlin.math.ceil(startTime / frameDuration)).toLong()
                val endFrame = (kotlin.math.floor(endTime / frameDuration)).toLong()
                val step = max(1L, (sampleRate / 10).toLong()) // Every 100ms
                for (f in startFrame..endFrame step step) {
                    val time = f * frameDuration
                    lines.add(GridLine(time, GridLine.Type.FRAME, formatFrame(f)))
                }
            }
            Mode.BEATS, Mode.BEATS_8TH, Mode.BEATS_16TH, Mode.BEATS_TRIPLET -> {
                val division = when (mode) {
                    Mode.BEATS -> 1.0
                    Mode.BEATS_8TH -> 0.5
                    Mode.BEATS_16TH -> 0.25
                    Mode.BEATS_TRIPLET -> 1.0 / 3.0
                    else -> 1.0
                }
                val gridDuration = beatDuration * division
                val startBeat = (kotlin.math.ceil(startTime / gridDuration)).toInt()
                val endBeat = (kotlin.math.floor(endTime / gridDuration)).toInt()
                for (b in startBeat..endBeat) {
                    val time = b * gridDuration
                    val beatInMeasure = (b % timeSignatureNumerator).toInt()
                    val measure = (b / timeSignatureNumerator).toInt() + 1
                    val label = "${measure}.${beatInMeasure + 1}"
                    lines.add(GridLine(time, GridLine.Type.BEAT, label))
                }
            }
            Mode.MEASURES -> {
                val startMeasure = (kotlin.math.ceil(startTime / measureDuration)).toInt()
                val endMeasure = (kotlin.math.floor(endTime / measureDuration)).toInt()
                for (m in startMeasure..endMeasure) {
                    val time = m * measureDuration
                    lines.add(GridLine(time, GridLine.Type.MEASURE, "${m + 1}"))
                }
            }
        }
        
        return lines
    }

    /** Format frame number as time string */
    private fun formatFrame(frame: Long): String {
        val timeSec = frame * frameDuration
        val mins = (timeSec / 60).toInt()
        val secs = (timeSec % 60).toInt()
        val ms = ((timeSec * 1000) % 1000).toInt()
        return if (mins > 0) String.format("%d:%02d.%03d", mins, secs, ms)
        else String.format("%d.%03d", secs, ms)
    }

    data class GridLine(
        val time: Double,
        val type: Type,
        val label: String
    ) {
        enum class Type {
            SECOND, FRAME, BEAT, MEASURE
        }
    }
}