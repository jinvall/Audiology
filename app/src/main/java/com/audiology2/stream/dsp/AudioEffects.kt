package com.audiology2.stream.dsp

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Serializable settings for the offline destructive effects chain.
 * All parameters default to "off / neutral" so an empty config is a no-op.
 */
@Serializable
data class EffectSettings(
    val highPassEnabled: Boolean = false,
    val highPassHz: Int = 80,
    val lowPassEnabled: Boolean = false,
    val lowPassHz: Int = 8000,
    val notchEnabled: Boolean = false,
    val notchHz: Double = 60.0,
    val notchQ: Double = 1.0,
    val eqEnabled: Boolean = false,
    val eqHz: Int = 1000,
    val eqGainDb: Double = 0.0,
    val eqBwOctaves: Double = 1.0,
    val compressorEnabled: Boolean = false,
    val thresholdDb: Double = -24.0,
    val ratio: Double = 4.0,
    val attackMs: Double = 5.0,
    val releaseMs: Double = 50.0,
    val makeUpGainDb: Double = 0.0,
    val limiterEnabled: Boolean = false,
    val limiterCeiling: Double = 0.98,
    val echoEnabled: Boolean = false,
    val echoDelayMs: Double = 320.0,
    val echoFeedback: Double = 0.35,
    val echoMix: Double = 0.25,
    val distortionEnabled: Boolean = false,
    val distortionDrive: Double = 6.0,
    val tremoloEnabled: Boolean = false,
    val tremoloRateHz: Double = 5.0,
    val tremoloDepth: Double = 0.6
) {
    val isNoOp: Boolean
        get() = !highPassEnabled && !lowPassEnabled && !notchEnabled && !eqEnabled &&
            !compressorEnabled && !limiterEnabled && !echoEnabled && !distortionEnabled && !tremoloEnabled

    companion object {
        val DEFAULT = EffectSettings()
    }
}

/**
 * Offline destructive effects: EQ/filters, dynamics, echo, distortion, tremolo.
 * Operates on interleaved float samples. Reuses the live DSP blocks
 * ([Biquad], [Compressor], [Limiter]) so offline and live processing match.
 */
object AudioEffects {

    /** Apply the configured chain. Returns a new buffer when anything changed. */
    fun apply(samples: FloatArray, sampleRate: Int, channels: Int, settings: EffectSettings): FloatArray {
        if (settings.isNoOp || samples.isEmpty()) return samples.copyOf()

        var out = samples.copyOf()

        if (settings.highPassEnabled) {
            out = runFilter(out, sampleRate, channels) {
                Biquad.highPass(settings.highPassHz.toDouble(), sampleRate.toDouble(), 0.7071)
            }
        }
        if (settings.lowPassEnabled) {
            out = runFilter(out, sampleRate, channels) {
                Biquad.lowPass(settings.lowPassHz.toDouble(), sampleRate.toDouble(), 0.7071)
            }
        }
        if (settings.notchEnabled) {
            out = runFilter(out, sampleRate, channels) {
                Biquad.notch(settings.notchHz, sampleRate.toDouble(), settings.notchQ.coerceIn(0.1, 20.0))
            }
        }
        if (settings.eqEnabled) {
            out = runFilter(out, sampleRate, channels) {
                Biquad.peak(settings.eqHz.toDouble(), sampleRate.toDouble(), settings.eqGainDb, settings.eqBwOctaves)
            }
        }
        if (settings.echoEnabled) {
            out = echo(out, sampleRate, channels, settings.echoDelayMs, settings.echoFeedback, settings.echoMix)
        }
        if (settings.distortionEnabled) {
            out = distortion(out, settings.distortionDrive)
        }
        if (settings.tremoloEnabled) {
            out = tremolo(out, sampleRate, channels, settings.tremoloRateHz, settings.tremoloDepth)
        }
        if (settings.compressorEnabled) {
            out = dynamics(out, sampleRate, channels, settings)
        }
        if (settings.limiterEnabled) {
            out = limiter(out, sampleRate, channels, settings.limiterCeiling)
        }
        return out
    }

    private fun runFilter(
        samples: FloatArray,
        sampleRate: Int,
        channels: Int,
        coeffs: () -> DoubleArray
    ): FloatArray {
        val processor = BiquadProcessor(coeffs())
        processor.configure(sampleRate, channels)
        val out = samples.copyOf()
        processor.process(out, 0, out.size / channels)
        return out
    }

    private fun echo(samples: FloatArray, sampleRate: Int, channels: Int, delayMs: Double, feedback: Double, mix: Double): FloatArray {
        val delaySamples = (delayMs / 1000.0 * sampleRate).toInt().coerceIn(50, sampleRate * 4)
        val fb = feedback.coerceIn(0.0, 0.95)
        val wet = mix.coerceIn(0.0, 1.0)
        val out = FloatArray(samples.size)
        // Ring buffer of delaySamples frames, interleaved by channel.
        val ring = FloatArray(delaySamples * channels)
        var writePos = 0
        var idx = 0
        val frames = samples.size / channels
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val s = f * channels + c
                val readPosFrames = (writePos - delaySamples).mod(delaySamples)
                val delayed = ring[readPosFrames * channels + c]
                val input = samples[s]
                ring[writePos * channels + c] = (input + delayed * fb).toFloat().coerceIn(-1f, 1f)
                out[s] = (input * (1.0 - wet) + delayed * wet).toFloat()
            }
            writePos++
            if (writePos >= delaySamples) writePos = 0
            idx += channels
        }
        return out
    }

    private fun distortion(samples: FloatArray, driveDb: Double): FloatArray {
        val k = (driveDb.coerceIn(0.0, 30.0) / 30.0) * 24.0 + 0.2
        val out = FloatArray(samples.size)
        val tanhK = kotlin.math.tanh(k)
        for (i in samples.indices) {
            val x = samples[i]
            // Soft clip: tanh approximation
            val y = kotlin.math.tanh(x * k) / tanhK
            out[i] = y.toFloat().coerceIn(-1f, 1f)
        }
        return out
    }

    private fun tremolo(samples: FloatArray, sampleRate: Int, channels: Int, rateHz: Double, depth: Double): FloatArray {
        val rate = rateHz.coerceIn(0.1, 30.0)
        val d = depth.coerceIn(0.0, 1.0)
        val out = FloatArray(samples.size)
        var frame = 0
        var idx = 0
        while (idx < samples.size) {
            val mod = 1.0 - d * (0.5 - 0.5 * sin(2.0 * Math.PI * rate * frame / sampleRate))
            val gain = mod.toFloat()
            for (c in 0 until channels) {
                if (idx + c < samples.size) out[idx + c] = samples[idx + c] * gain
            }
            idx += channels
            frame++
        }
        return out
    }

    private fun dynamics(samples: FloatArray, sampleRate: Int, channels: Int, s: EffectSettings): FloatArray {
        val compressor = Compressor(
            thresholdDb = s.thresholdDb,
            ratio = s.ratio,
            attackMs = s.attackMs,
            releaseMs = s.releaseMs,
            makeUpGainDb = s.makeUpGainDb
        )
        compressor.configure(sampleRate, channels)
        val out = samples.copyOf()
        compressor.process(out, 0, out.size / channels)
        return out
    }

    private fun limiter(samples: FloatArray, sampleRate: Int, channels: Int, ceiling: Double): FloatArray {
        val lim = Limiter(ceiling = ceiling.coerceIn(0.01, 1.0), releaseMs = 25.0)
        lim.configure(sampleRate, channels)
        val out = samples.copyOf()
        lim.process(out, 0, out.size / channels)
        return out
    }

    /** Soft-knee noise gate: below threshold, gain is ducked. */
    fun gate(samples: FloatArray, sampleRate: Int, channels: Int, thresholdDb: Double, releaseMs: Double = 80.0): FloatArray {
        val threshold = Math.pow(10.0, thresholdDb / 20.0)
        val releaseAlpha = exp(-1.0 / (releaseMs / 1000.0 * sampleRate))
        val out = FloatArray(samples.size)
        val env = FloatArray(channels) { 1f }
        var idx = 0
        var frame = 0
        while (idx < samples.size) {
            for (c in 0 until channels) {
                val x = samples[idx + c]
                val target = if (abs(x) >= threshold) 1f else 0f
                env[c] = if (target > env[c]) target else env[c] * releaseAlpha.toFloat() + (1 - releaseAlpha).toFloat() * target
                out[idx + c] = x * env[c]
            }
            idx += channels
            frame++
        }
        return out
    }
}
