package com.audiology2.stream.ui

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Gain envelope for clip volume automation.
 * Supports multiple breakpoints for volume changes over time.
 */
@Serializable
data class GainEnvelope(
    val id: String = UUID.randomUUID().toString(),
    val clipId: String,
    /** Points: (time, gain) where time is relative to clip start, gain is 0.0-2.0 */
    val points: MutableList<GainPoint> = mutableListOf()
) {

    /** Add or update a point */
    fun setPoint(time: Double, gain: Float) {
        val clampedGain = gain.coerceIn(0f, 2f)
        val clampedTime = time.coerceAtLeast(0.0)
        
        val existingIndex = points.indexOfFirst { it.time == clampedTime }
        if (existingIndex >= 0) {
            points[existingIndex] = GainPoint(clampedTime, clampedGain)
        } else {
            points.add(GainPoint(clampedTime, clampedGain))
            points.sortBy { it.time }
        }
    }

    /** Remove point at time */
    fun removePoint(time: Double) {
        points.removeAll { it.time == time }
    }

    /** Get interpolated gain at time */
    fun getGainAt(time: Double): Float {
        if (points.isEmpty()) return 1f
        if (time <= points.first().time) return points.first().gain
        if (time >= points.last().time) return points.last().gain
        
        // Linear interpolation
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            if (time >= p1.time && time <= p2.time) {
                val t = if (p2.time == p1.time) 0.0 else (time - p1.time) / (p2.time - p1.time)
                return p1.gain + (p2.gain - p1.gain) * t.toFloat()
            }
        }
        return points.last().gain
    }

    /** Create default envelope (unity gain) */
    companion object {
        fun createDefault(clipId: String, duration: Double): GainEnvelope {
            return GainEnvelope(clipId = clipId, points = mutableListOf(
                GainPoint(0.0, 1.0f),
                GainPoint(duration, 1.0f)
            ))
        }
    }
}

/** Single point in gain envelope */
@Serializable
data class GainPoint(
    val time: Double,  // Relative to clip start (seconds)
    val gain: Float    // 0.0 = -inf, 1.0 = unity, 2.0 = +6dB
) {
    init {
        require(time >= 0.0) { "Time must be >= 0" }
        require(gain in 0f..2f) { "Gain must be 0.0 to 2.0" }
    }
}

/** Extension for applying gain envelope to audio */
fun FloatArray.applyGainEnvelope(envelope: GainEnvelope, sampleRate: Int, channels: Int, startTimeSec: Double = 0.0) {
    val totalFrames = size / channels
    for (i in 0 until totalFrames) {
        val timeSec = startTimeSec + i.toDouble() / sampleRate
        val gain = envelope.getGainAt(timeSec)
        for (ch in 0 until channels) {
            this[i * channels + ch] *= gain
        }
    }
}