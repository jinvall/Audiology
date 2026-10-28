package com.audiology2.stream.dsp

import kotlin.math.abs

/**
 * Brickwall lookahead-free limiter. Fast attack, release, hard ceiling.
 * Works by computing a peak envelope and reducing gain so the output never
 * exceeds [ceiling] (0 dBFS full-scale by default). This is a "soft" limiter
 * with a fast release to avoid the pumping that destroys quiet transients.
 */
class Limiter(
    var ceiling: Double = 1.0,          // linear full-scale ceiling (1.0 = 0 dBFS)
    var releaseMs: Double = 25.0
) : AudioProcessor {
    private var releaseAlpha = 1.0
    private var channels = 1
    private var ceilings = DoubleArray(1) { ceiling }
    private var releaseAlphaPerCh = DoubleArray(1) { 1.0 }
    private val envelope = DoubleArray(2) { 0.0 }

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        val rel = releaseMs.coerceIn(1.0, 1000.0)
        releaseAlpha = exp(-1.0 / (rel / 1000.0 * sampleRate))
        releaseAlphaPerCh = DoubleArray(channels.coerceAtLeast(1)) { releaseAlpha }
        ceilings = DoubleArray(channels.coerceAtLeast(1)) { ceiling }
    }

    private fun exp(x: Double): Double = kotlin.math.exp(x)

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val x = buffer[idx]
                val env = envelope[c]
                // attack near-instant on the positive peak
                val absX = abs(x.toDouble())
                val newEnv = if (absX > env) absX else releaseAlphaPerCh[c] * env + (1 - releaseAlphaPerCh[c]) * absX
                envelope[c] = newEnv
                val ceil = ceilings[c]
                // gain so output <= ceil; add tiny headroom
                val gain = if (newEnv > ceil) ceil / newEnv else 1.0
                buffer[idx] = (x * gain).toFloat()
                idx++
            }
        }
    }
}
