package com.audiology2.stream.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpectralEngineTest {

    /** Build a normalized-magnitude frame for a synthetic tone (mirrors FFTProcessor output). */
    private fun frameFor(freqHz: Double, sampleRate: Int = 48000, fftSize: Int = 2048): FloatArray {
        val n = fftSize / 2
        val out = FloatArray(n)
        val bin = (freqHz * fftSize / sampleRate).toInt()
        if (bin < n) out[bin] = 0.9f
        return out
    }

    private fun frameForSine(freqHz: Double, sampleRate: Int = 48000, fftSize: Int = 2048): FloatArray {
        // Synthesize a harmonic-rich tone (fundamental + 4 partials) with a
        // Hann window - HPS needs harmonics to multiply, a pure sine lacks them.
        val n = fftSize / 2
        val out = FloatArray(n)
        val samples = DoubleArray(fftSize)
        val partials = listOf(1.0, 0.5, 0.33, 0.25, 0.2)
        for (i in 0 until fftSize) {
            val w = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (fftSize - 1))
            var s = 0.0
            partials.forEachIndexed { idx, amp ->
                s += amp * sin(2.0 * PI * freqHz * (idx + 1) * i / sampleRate)
            }
            samples[i] = s * w
        }
        var magMax = 1e-12
        val mags = DoubleArray(n)
        for (k in 1 until n) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until fftSize) {
                val a = 2.0 * PI * k * i / fftSize
                re += samples[i] * kotlin.math.cos(a)
                im -= samples[i] * sin(a)
            }
            mags[k] = kotlin.math.sqrt(re * re + im * im)
            if (mags[k] > magMax) magMax = mags[k]
        }
        for (k in 1 until n) {
            val rel = (mags[k] / magMax).coerceAtMost(1.0)
            out[k] = ((20.0 * kotlin.math.log10(rel.coerceAtLeast(1e-8)) + 120.0) / 120.0).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    @Test
    fun peak_findsToneFrequency() {
        val frame = frameFor(421.875) // bin-aligned at 48k/2048 (freq = bin 18)
        val peak = SpectralEngine.peak(frame, 48000, 2048)
        assertNotNull(peak)
        assertEquals(421.875, peak.freq, 2.0)
    }

    @Test
    fun fundamental_estimatesSine() {
        val frame = frameForSine(330.0)
        val f0 = SpectralEngine.fundamental(frame, 48000, 2048)
        assertNotNull(f0)
        assertEquals(330.0, f0!!, 12.0)
    }

    @Test
    fun noteFromFreq_a440() {
        val note = SpectralEngine.noteFromFreq(440.0)
        assertEquals(69, note.midi)
        assertEquals("A4", note.name)
        assertTrue(kotlin.math.abs(note.cents) <= 2)
    }

    @Test
    fun noteFromFreq_middleC() {
        val note = SpectralEngine.noteFromFreq(261.6)
        assertEquals(60, note.midi)
        assertEquals("C4", note.name)
        assertTrue(kotlin.math.abs(note.cents) <= 3)
    }

    @Test
    fun centroid_positiveForBrightSignal() {
        val frame = frameFor(2000.0)
        val c = SpectralEngine.centroid(frame, 48000, 2048)
        assertTrue(c > 500.0)
    }

    @Test
    fun rms_zeroForSilence() {
        assertEquals(0.0, SpectralEngine.rms(FloatArray(1000)), 1e-6)
    }
}