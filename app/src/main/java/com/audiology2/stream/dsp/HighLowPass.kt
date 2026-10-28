package com.audiology2.stream.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * High-pass and low-pass filter processors built from a fixed-coefficient
 * biquad (Butterworth, Q=0.7071 by default). The coefficient set is recomputed
 * at construction for the requested sample rate / corner frequency.
 */
class HighPassFilter(hz: Double, sampleRate: Double, private val q: Double = 0.7071) : AudioProcessor {
    private val coeffs = Biquad.highPass(hz, sampleRate, q)
    private var processor = BiquadProcessor(coeffs)

    override fun configure(sampleRate: Int, channels: Int) {
        processor = BiquadProcessor(coeffs)
        processor.configure(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        processor.process(buffer, offset, frameCount)
    }
}

class LowPassFilter(hz: Double, sampleRate: Double, private val q: Double = 0.7071) : AudioProcessor {
    private val coeffs = Biquad.lowPass(hz, sampleRate, q)
    private var processor = BiquadProcessor(coeffs)

    override fun configure(sampleRate: Int, channels: Int) {
        processor = BiquadProcessor(coeffs)
        processor.configure(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        processor.process(buffer, offset, frameCount)
    }
}

/**
 * Configurable notch (band-stop) filter. The processor is rebuilt whenever the
 * frequency or Q changes so the chain can swap it in live.
 */
class NotchFilter(var hz: Double, var q: Double, sampleRate: Double) : AudioProcessor {
    private val sampleRate = sampleRate
    private var coeffs = Biquad.notch(hz, sampleRate, q)
    private var processor = BiquadProcessor(coeffs)

    fun update(hz: Double, q: Double) {
        require(q > 0.0) { "Q must be positive" }
        this.hz = hz
        this.q = q
        this.coeffs = Biquad.notch(hz, sampleRate, q)
        rebuild()
    }

    private fun rebuild() {
        this.processor = BiquadProcessor(coeffs)
    }

    override fun configure(sampleRate: Int, channels: Int) {
        processor = BiquadProcessor(coeffs)
        processor.configure(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        processor.process(buffer, offset, frameCount)
    }
}

/**
 * Parametric peaking EQ. [gainDb] positive = boost, negative = cut.
 * [bwOctaves] controls bandwidth; Q is derived via the cookbook approximation.
 */
class Equalizer(
    var hz: Double,
    var gainDb: Double,
    var bwOctaves: Double,
    sampleRate: Double
) : AudioProcessor {
    private val sampleRate = sampleRate
    private var coeffs = Biquad.peak(hz, sampleRate, gainDb, bwOctaves)
    private var processor = BiquadProcessor(coeffs)

    fun update(hz: Double, gainDb: Double, bwOctaves: Double) {
        require(gainDb in -36.0..36.0) { "gain out of range" }
        require(bwOctaves > 0.0) { "bandwidth must be > 0" }
        this.hz = hz
        this.gainDb = gainDb
        this.bwOctaves = bwOctaves
        this.coeffs = Biquad.peak(hz, sampleRate, gainDb, bwOctaves)
        rebuild()
    }

    private fun rebuild() {
        this.processor = BiquadProcessor(coeffs)
    }

    override fun configure(sampleRate: Int, channels: Int) {
        processor = BiquadProcessor(coeffs)
        processor.configure(sampleRate, channels)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        processor.process(buffer, offset, frameCount)
    }
}
