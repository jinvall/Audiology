package com.audiology2.stream.ui

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure math behind the console controls (knob value mapping, meter scaling).
 * Android-free so it is unit-testable, mirroring SpectralEngine's approach.
 */
object ConsoleMath {

    /** Clamp [v] into [lo, hi]. */
    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    /**
     * Map a normalized position (0..1) to a value in [min,max], snapping to
     * [step] when step > 0.
     */
    fun positionToValue(position: Float, min: Double, max: Double, step: Double = 0.0): Double {
        val p = clamp(position.toDouble(), 0.0, 1.0)
        val raw = min + p * (max - min)
        val snapped = if (step > 0.0) {
            val steps = Math.round((raw - min) / step)
            min + steps * step
        } else raw
        return clamp(snapped, min, max)
    }

    /** Map a value to a normalized position (0..1) within [min,max]. */
    fun valueToPosition(value: Double, min: Double, max: Double): Float {
        if (max <= min) return 0f
        return clamp((value - min) / (max - min), 0.0, 1.0).toFloat()
    }

    /** Linear amplitude -> dBFS (floor at [floorDb]). */
    fun linearToDb(amp: Double, floorDb: Double = -120.0): Double =
        if (amp <= 0.0) floorDb else (20.0 * log10(amp)).coerceAtLeast(floorDb)

    /** dBFS -> linear amplitude. */
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /**
     * Map a dB value to a meter fraction (0..1) with [floorDb] at 0 and 0 dBFS
     * at 1. Values below the floor clamp to 0.
     */
    fun dbToMeterFraction(db: Double, floorDb: Double = -60.0): Float {
        if (db <= floorDb) return 0f
        if (db >= 0.0) return 1f
        return ((db - floorDb) / (0.0 - floorDb)).toFloat()
    }

    /** Whether [amp] is at/over full scale (clipping). */
    fun isClipping(amp: Double, threshold: Double = 0.999): Boolean = abs(amp) >= threshold
}
