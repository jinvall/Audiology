package com.audiology2.stream.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectralTransformTest {

    private fun tone(freqHz: Double, sampleRate: Int, frames: Int, amp: Double = 0.8): FloatArray {
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            out[i] = (amp * sin(2.0 * PI * freqHz * i / sampleRate)).toFloat()
        }
        return out
    }

    @Test
    fun roundTrip_recoversSineWithinTolerance() {
        val sr = 48000
        val fft = 2048
        val hop = 512
        val samples = tone(440.0, sr, sr / 2) // 0.5s of 440Hz
        val frames = SpectralTransform.stft(samples, fft, hop)
        assertTrue(frames.size > 10)
        val recovered = SpectralTransform.istft(frames, fft, hop)

        // Compare the middle section (edge effects from boundary frames).
        val midStart = fft
        val midEnd = minOf(samples.size - fft, recovered.size - fft)
        var errSum = 0.0
        for (i in midStart until midEnd) {
            errSum += abs(samples[i] - recovered[i])
        }
        val count = (midEnd - midStart).coerceAtLeast(1)
        val rmsErr = errSum / count
        assertTrue(rmsErr < 0.15, "round-trip RMS error was $rmsErr")
    }

    @Test
    fun maskMutesSelectedBand() {
        val sr = 48000
        val fft = 2048
        val hop = 512
        val samples = tone(440.0, sr, sr / 4)
        val frames = SpectralTransform.stft(samples, fft, hop)

        // Mute everything above 1kHz -> should basically remove 440Hz content.
        val mask = SpectralTransform.rectangularMask(frames.size, fft, sr, 0, frames.size, 0.0, 1000.0, 1e-6)
        val filtered = SpectralTransform.applyMask(frames, fft, sr, mask)
        val recovered = SpectralTransform.istft(filtered, fft, hop)

        var orig = 0.0
        var out = 0.0
        val midStart = fft
        val midEnd = minOf(samples.size - fft, recovered.size - fft)
        for (i in midStart until midEnd) {
            orig += abs(samples[i])
            out += abs(recovered[i])
        }
        // Energy should drop dramatically (440Hz at -24dB+ is below noise).
        val ratio = out.coerceAtLeast(1e-9) / orig.coerceAtLeast(1e-9)
        assertTrue(ratio < 0.5, "masked energy ratio was $ratio")
    }

    @Test
    fun hann_isColaCompliantAt75Overlap() {
        val win = SpectralTransform.hann(2048)
        val hop = 512
        // Steady state: for positions deep inside the signal, every position is
        // covered by 4 windows (hop = N/4), so sum of squared windows is constant.
        val base = 4 * hop
        val sums = FloatArray(hop)
        val frameStarts = intArrayOf(1 * hop, 2 * hop, 3 * hop, 4 * hop, 5 * hop, 6 * hop, 7 * hop)
        for (i in 0 until hop) {
            val pos = base + i
            var s = 0.0
            for (start in frameStarts) {
                val idx = pos - start
                if (idx in 0 until win.size) s += win[idx] * win[idx]
            }
            sums[i] = s.toFloat()
        }
        val avg = sums.average()
        var maxDev = 0.0
        for (s in sums) maxDev = maxOf(maxDev, abs(s - avg) / avg)
        assertTrue(maxDev < 0.05, "COLA deviation was $maxDev")
    }
}
