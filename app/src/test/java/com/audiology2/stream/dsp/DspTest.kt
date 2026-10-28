package com.audiology2.stream.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DspTest {

    private fun sine(samples: Int, freq: Double, sr: Double): FloatArray {
        return FloatArray(samples) { i ->
            (0.5 * sin(2 * PI * freq * i / sr)).toFloat()
        }
    }

    @Test
    fun gainProcessorAmplifies() {
        val g = GainProcessor(12.0)
        g.configure(48000, 1)
        val buf = FloatArray(100) { 0.1f }
        g.process(buf, 0, 100)
        assertTrue(abs(buf[0] - 0.1f * GainProcessor.dbToLinear(12.0)) < 1e-5)
    }

    @Test
    fun gainProcessorClipsAtOne() {
        val g = GainProcessor(24.0)
        g.configure(48000, 1)
        val buf = FloatArray(1) { 0.9f }
        g.process(buf, 0, 1)
        assertTrue(buf[0] <= 1.0f)
    }

    @Test
    fun highPassRemovesDC() {
        val hp = HighPassFilter(80.0, 48000.0)
        hp.configure(48000, 1)
        val buf = FloatArray(48000) { 1.0f } // pure DC
        hp.process(buf, 0, 48000)
        // after a 1s DC, an HPF should nearly zero it
        // A high-pass excited from zero initial state rings at the corner
        // frequency; only the steady-state tail should reject DC. Skip the
        // startup transient (~40 ms at 48 kHz) before measuring.
        val skip = 48000 / 25
        var max = 0.0
        for (i in skip until buf.size) max = kotlin.math.max(max, abs(buf[i].toDouble()))
        assertTrue(max < 0.05, "DC residual $max too large")
    }

    @Test
    fun lowPassAttenuatesHighFreq() {
        val lp = LowPassFilter(1000.0, 48000.0)
        lp.configure(48000, 1)
        var high = sine(48000, 8000.0, 48000.0) // well above cutoff
        var dc = sine(48000, 50.0, 48000.0)
        lp.process(high, 0, 48000)
        lp.process(dc, 0, 48000)
        val highEnergy = high.sumOf { it.toDouble() * it.toDouble() }
        val dcEnergy = dc.sumOf { it.toDouble() * it.toDouble() }
        // 8kHz should be attenuated below 50Hz
        assertTrue(highEnergy < dcEnergy * 0.01, "HPF/LPF not attenuating: $highEnergy vs $dcEnergy")
    }

    @Test
    fun notchKillsCenterFrequency() {
        val notch = NotchFilter(60.0, 10.0, 48000.0)
        notch.configure(48000, 1)
        val buf = sine(48000, 60.0, 48000.0)
        // 60 Hz / Q=10 has a long zero-state startup transient (~2.5k samples);
        // measure energy over the steady-state second half only.
        val tail = buf.size / 2 until buf.size
        var before = 0.0
        for (i in tail) before += buf[i].toDouble() * buf[i].toDouble()
        notch.process(buf, 0, 48000)
        var after = 0.0
        for (i in tail) after += buf[i].toDouble() * buf[i].toDouble()
        assertTrue(after < before * 0.01, "notch did not suppress 60Hz: $after vs $before")
    }

    @Test
    fun compressorReducesPeaks() {
        val comp = Compressor(thresholdDb = -20.0, ratio = 4.0, attackMs = 1.0, releaseMs = 100.0)
        comp.configure(48000, 1)
        val buf = sine(48000, 50.0, 48000.0) // 0.5 amplitude = -6 dBFS, above -20 threshold
        comp.process(buf, 0, 48000)
        // peak should be reduced below the input peak (0.5)
        var pk = 0.0
        for (v in buf) pk = kotlin.math.max(pk, abs(v.toDouble()))
        assertTrue(pk < 0.5, "compressor did not reduce peak: $pk")
    }

    @Test
    fun limiterCapsAtCeiling() {
        val lim = Limiter(ceiling = 0.5, releaseMs = 25.0)
        lim.configure(48000, 1)
        val buf = FloatArray(48000) { 1.0f }
        lim.process(buf, 0, 48000)
        var pk = 0.0
        for (v in buf) pk = kotlin.math.max(pk, abs(v.toDouble()))
        assertTrue(pk <= 0.5 + 1e-3, "limiter exceeded ceiling: $pk")
    }

    @Test
    fun processingChainBypassIsNoOp() {
        val chain = ProcessingChain(48000, 1).apply {
            currentConfig = AudioConfig(inputGainDb = 24.0, highPassEnabled = true, highPassHz = 80)
            bypass = true
        }
        val buf = FloatArray(480) { 0.5f }
        chain.process(buf, 0, 480)
        // bypass -> unchanged
        for (v in buf) assertEquals(0.5f, v, 1e-6f)
    }

    @Test
    fun processingChainAppliesGain() {
        val chain = ProcessingChain(48000, 1).apply {
            currentConfig = AudioConfig(inputGainDb = 6.0)
            bypass = false
        }
        val buf = FloatArray(100) { 0.1f }
        chain.process(buf, 0, 100)
        val expected = (0.1 * GainProcessor.dbToLinear(6.0)).toFloat()
        assertTrue(abs(buf[0] - expected) < 1e-4, "${buf[0]} vs $expected")
    }

    @Test
    fun eqPeakCoefficientsValid() {
        val c = Biquad.peak(1000.0, 48000.0, 6.0, 1.0)
        assertEquals(5, c.size)
        assertTrue(c[0] > 0.0)
    }
}
