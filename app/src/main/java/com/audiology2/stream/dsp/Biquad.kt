package com.audiology2.stream.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order (biquad) IIR filter using transposed Direct Form II
 * (numerically stable and allocation-free). Coefficients follow the
 * RBJ "Audio EQ Cookbook". A single instance is per-channel stateful.
 *
 * For multi-channel processing the chain creates one Biquad per channel so
 * per-channel history is preserved correctly.
 */
class Biquad {
    // Transposed Direct Form II state
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    fun setCoefficients(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        this.b0 = b0
        this.b1 = b1
        this.b2 = b2
        this.a1 = a1
        this.a2 = a2
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }

    fun processOne(sample: Double): Double {
        val y = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = sample; y2 = y1; y1 = y
        return y
    }

    companion object {
        private const val LN2 = 0.6931471805599453

        /** Convert fractional bandwidth in octaves to Q (cookbook approximation). */
        fun bandwidthToQ(bwOctaves: Double): Double {
            val bw = bwOctaves.coerceIn(0.05, 10.0)
            val twoBw = Math.pow(2.0, bw)
            return sqrt(twoBw) / (twoBw - 1.0)
        }

        /** db gain -> linear for the peaking shelffactor A = 10^(gain/40). */
        fun dbToAmp(gainDb: Double): Double = Math.pow(10.0, gainDb / 40.0)

        /** Low-pass filter coefficients (12 dB/octave) at [f0]Hz, [sampleRate]. */
        fun lowPass(f0: Double, sampleRate: Double, q: Double = 0.7071): DoubleArray {
            require(f0 > 0 && f0 < sampleRate / 2.0) { "f0 $f0 out of range" }
            val omega = 2 * PI * f0 / sampleRate
            val sin = sin(omega); val cos = cos(omega)
            val alpha = sin / (2 * q)
            val a0 = 1 + alpha
            return doubleArrayOf(
                (1 - cos) / 2 / a0,
                (1 - cos) / a0,
                (1 - cos) / 2 / a0,
                (-2 * cos) / a0,
                (1 - alpha) / a0
            )
        }

        /** High-pass filter coefficients. */
        fun highPass(f0: Double, sampleRate: Double, q: Double = 0.7071): DoubleArray {
            require(f0 > 0 && f0 < sampleRate / 2.0) { "f0 $f0 out of range" }
            val omega = 2 * PI * f0 / sampleRate
            val sin = sin(omega); val cos = cos(omega)
            val alpha = sin / (2 * q)
            val a0 = 1 + alpha
            return doubleArrayOf(
                (1 + cos) / 2 / a0,
                -(1 + cos) / a0,
                (1 + cos) / 2 / a0,
                (-2 * cos) / a0,
                (1 - alpha) / a0
            )
        }

        /** Notch (band-stop) filter at [f0]Hz. */
        fun notch(f0: Double, sampleRate: Double, q: Double): DoubleArray {
            require(f0 > 0 && f0 < sampleRate / 2.0) { "f0 $f0 out of range" }
            val omega = 2 * PI * f0 / sampleRate
            val sin = sin(omega); val cos = cos(omega)
            val alpha = sin / (2 * q)
            val a0 = 1 + alpha
            return doubleArrayOf(
                1 / a0,
                (-2 * cos) / a0,
                1 / a0,
                (-2 * cos) / a0,
                (1 - alpha) / a0
            )
        }

        /** Peaking EQ at [f0]Hz with [gainDb] dB, Q from [bwOctaves]. */
        fun peak(f0: Double, sampleRate: Double, gainDb: Double, bwOctaves: Double): DoubleArray {
            require(f0 > 0 && f0 < sampleRate / 2.0) { "f0 $f0 out of range" }
            val a = dbToAmp(gainDb)
            val q = bandwidthToQ(bwOctaves)
            val omega = 2 * PI * f0 / sampleRate
            val sin = sin(omega); val cos = cos(omega)
            val alpha = sin / (2 * q)
            val a0 = 1 + alpha / a
            return doubleArrayOf(
                (1 + alpha * a) / a0,
                (-2 * cos) / a0,
                (1 - alpha * a) / a0,
                (-2 * cos) / a0,
                (1 - alpha / a) / a0
            )
        }
    }
}

/**
 * Generic stateful filter that owns one [Biquad] per channel and applies a
 * fixed coefficient set. Factory methods build specific filter types.
 */
class BiquadProcessor(
    private val coefficients: DoubleArray
) : AudioProcessor {
    private var filters: List<Biquad> = emptyList()

    // Accessors for testing
    val b0 get() = coefficients[0]
    val b1 get() = coefficients[1]
    val b2 get() = coefficients[2]
    val a1 get() = coefficients[3]
    val a2 get() = coefficients[4]

    override fun configure(sampleRate: Int, channels: Int) {
        this.filters = List(channels) { Biquad() }
        for (f in filters) {
            f.setCoefficients(coefficients[0], coefficients[1], coefficients[2],
                coefficients[3], coefficients[4])
        }
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        val ch = filters.size
        if (ch == 0) return
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until ch) {
                buffer[idx] = filters[c].processOne(buffer[idx].toDouble()).toFloat()
                idx++
            }
        }
    }
}
