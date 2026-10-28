package com.audiology2.stream.dsp

import kotlin.math.*

/**
 * Dynamics compressor with adjustable threshold, ratio, attack and release.
 * Uses a per-channel peak-following envelope. Time-to-peak via
 * alpha = exp(-1/(t*sr)). Make-up gain is applied after compression.
 */
class Compressor(
    private var thresholdDb: Double = -24.0,
    var ratio: Double = 4.0,
    var attackMs: Double = 5.0,
    var releaseMs: Double = 50.0,
    var makeUpGainDb: Double = 0.0
) : AudioProcessor {
    private var attackAlpha = 1.0
    private var releaseAlpha = 1.0
    private var makeUp = 1.0
    private var channels = 1
    private var envelopes = DoubleArray(1) { -96.0 } // in dBFS

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        val atk = attackMs.coerceIn(0.1, 500.0)
        val rel = releaseMs.coerceIn(1.0, 2000.0)
        attackAlpha = exp(-1.0 / (atk / 1000.0 * sampleRate))
        releaseAlpha = exp(-1.0 / (rel / 1000.0 * sampleRate))
        thresholdDb = thresholdDb.coerceIn(-60.0, 0.0)
        makeUp = GainProcessor.dbToLinear(makeUpGainDb.coerceIn(-12.0, 24.0))
        envelopes = DoubleArray(channels.coerceAtLeast(1)) { -96.0 }
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        val r = ratio.coerceIn(1.0, 20.0)
        val invR = 1.0 / r
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val x = buffer[idx]
                val absX = abs(x.toDouble())
                val xDb = if (absX < 1e-7) -96.0 else 20.0 * log10(absX)
                val env = envelopes[c]
                val newEnv = if (xDb > env) env + attackAlpha * (xDb - env)
                    else env + releaseAlpha * (xDb - env)
                envelopes[c] = newEnv
                // Gain computer: output_dB = threshold + (env - threshold)/ratio when env > threshold
                val gainDb = if (newEnv > thresholdDb) {
                    thresholdDb + (newEnv - thresholdDb) * invR - newEnv
                } else 0.0
                val gain = GainProcessor.dbToLinear(gainDb) * makeUp
                buffer[idx] = (x * gain).toFloat().coerceIn(-1.0f, 1.0f)
                idx++
            }
        }
    }
}
