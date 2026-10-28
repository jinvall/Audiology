package com.audiology2.stream.dsp

/**
 * Simple software AGC (leveler): adjusts a per-channel gain envelope to bring
 * the signal toward a target RMS (-24 dBFS). Gain changes slowly to avoid
 * pumping; ceiling-limited so it cannot cause clipping. Used as a fallback
 * when the platform AutomaticGainControl is unavailable.
 */
class AgcProcessor(
    var targetRmsDb: Double = -24.0,
    var maxGainDb: Double = 24.0,
    var minGainDb: Double = -12.0,
    var attackMs: Double = 50.0,
    var releaseMs: Double = 200.0
) : AudioProcessor {
    private var attackAlpha = 1.0
    private var releaseAlpha = 1.0
    private var target = 1.0
    private var maxG = 1.0
    private var minG = 1.0
    private var channels = 1
    private var gains = DoubleArray(1) { 1.0 }

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        attackAlpha = exp(-1.0 / (attackMs / 1000.0 * sampleRate))
        releaseAlpha = exp(-1.0 / (releaseMs / 1000.0 * sampleRate))
        target = GainProcessor.dbToLinear(targetRmsDb.coerceIn(-60.0, 0.0))
        maxG = GainProcessor.dbToLinear(maxGainDb.coerceIn(-24.0, 40.0))
        minG = GainProcessor.dbToLinear(minGainDb.coerceIn(-24.0, 12.0))
        gains = DoubleArray(channels.coerceAtLeast(1)) { 1.0 }
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val x = buffer[idx]
                val g = gains[c]
                var y = (x * g).toFloat()
                if (y > 1.0f) y = 1.0f else if (y < -1.0f) y = -1.0f
                buffer[idx] = y
                val out = kotlin.math.abs(y.toDouble())
                val ratio = if (target > 0) (out / target).coerceAtLeast(0.0).coerceAtMost(2.0) else 1.0
                val newG = if (out < target) {
                    attackAlpha * g + (1 - attackAlpha) * (g * ratio)
                } else {
                    releaseAlpha * g + (1 - releaseAlpha) * (g * ratio)
                }
                gains[c] = newG.coerceIn(minG, maxG)
                idx++
            }
        }
    }

    private fun exp(x: Double): Double = kotlin.math.exp(x)
}

/**
 * Downward noise gate: attenuates frames whose energy is below [thresholdDb]
 * by a [ratio] factor. Software approximation of noise suppression; can erase
 * quiet transients, so OFF by default in Maximum Sensitivity mode.
 */
class NoiseSuppressor(
    var thresholdDb: Double = -50.0,
    var ratio: Double = 4.0,
    var attackMs: Double = 5.0,
    var releaseMs: Double = 100.0
) : AudioProcessor {
    private var attackAlpha = 1.0
    private var releaseAlpha = 1.0
    private var threshold = 1.0
    private var invRatio = 1.0
    private var channels = 1
    private var envelopes = DoubleArray(1) { 0.0 }

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        attackAlpha = exp(-1.0 / (attackMs / 1000.0 * sampleRate))
        releaseAlpha = exp(-1.0 / (releaseMs / 1000.0 * sampleRate))
        threshold = GainProcessor.dbToLinear(thresholdDb.coerceIn(-90.0, 0.0))
        invRatio = 1.0 / ratio.coerceIn(1.0, 32.0)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                val x = buffer[idx]
                val absX = kotlin.math.abs(x.toDouble())
                val env = envelopes[c]
                val newEnv = if (absX > env) env + attackAlpha * (absX - env)
                    else env + releaseAlpha * (absX - env)
                envelopes[c] = newEnv
                val gainDb = if (newEnv >= threshold) 0.0
                else {
                    val rel = newEnv / threshold
                    val d = 1.0 - rel
                    if (d <= 0.0) 0.0 else -d * 48.0 * (1 - invRatio)
                }
                val gain = GainProcessor.dbToLinear(gainDb)
                buffer[idx] = (x * gain).toFloat().coerceIn(-1.0f, 1.0f)
                idx++
            }
        }
    }

    private fun exp(x: Double): Double = kotlin.math.exp(x)
}
