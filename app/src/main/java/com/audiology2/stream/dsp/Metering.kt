package com.audiology2.stream.dsp

import kotlin.math.*

/**
 * Running RMS and peak analysis over a float buffer. Used for the input/output
 * meters in the UI and for silence detection. All state is reset on [reset].
 */
class Metering {
    private var sumSq = 0.0
    private var peak = 0.0
    private var n = 0
    private var clipped = false
    private var active = false  // any sample above silence threshold

    /** Silence floor in linear amplitude (default -90 dBFS). */
    var silenceFloorLinear: Double = GainProcessor.dbToLinear(-90.0)
        set(v) { field = v.coerceIn(0.0, 1.0) }

    fun process(buffer: FloatArray, offset: Int, frameCount: Int, channels: Int) {
        val end = offset + frameCount * channels
        var s = sumSq
        var p = peak
        var nn = n
        for (i in offset until end) {
            val x = buffer[i].toDouble()
            val ax = abs(x)
            s += x * x
            nn++
            if (ax > p) p = ax
            if (ax > silenceFloorLinear) active = true
            if (ax > 0.999) clipped = true
        }
        sumSq = s
        peak = p
        n = nn
    }

    val rmsLinear: Double get() = if (n == 0) 0.0 else sqrt(sumSq / n)
    val rmsDb: Double get() = if (rmsLinear <= 0.0) -96.0 else 20.0 * log10(rmsLinear)
    val peakLinear: Double get() = peak
    val peakDb: Double get() = if (peak <= 0.0) -96.0 else 20.0 * log10(peak)
    val isClipping: Boolean get() = clipped
    val isActive: Boolean get() = active

    fun reset() {
        sumSq = 0.0
        peak = 0.0
        n = 0
        clipped = false
        active = false
    }

    companion object {
        /** Recommended 300ms-ish window size given a sample rate. */
        fun windowSamples(sampleRate: Int, ms: Double = 300.0): Int =
            (sampleRate * ms / 1000.0).toInt().coerceAtLeast(1)
    }
}

/** Convenience: dBFS rounded to 1 decimal, or "—" when silent. */
fun Double.dbDisplay(): String =
    if (this < -90.0) "—" else "%.1f dBFS".format(this)
