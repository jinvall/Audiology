package com.audiology2.stream.dsp

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Short-Time Fourier Transform and inverse (windowed, overlap-add) with
 * magnitude/phase decomposition - the foundation for spectral editing
 * (brush masks, band surgical EQ, harmonic resynthesis).
 *
 * Pure Kotlin, no Android deps. [stft] splits a mono signal into
 * [SpectralFrame]s; [istft] reassembles frames back to time-domain with a
 * Hann window and 75% overlap (COLA-satisfying window for clean recovery).
 */
object SpectralTransform {

    const val DEFAULT_FFT = 2048
    const val DEFAULT_HOP = 512 // 75% overlap

    /** Windowed STFT of a mono signal. Frames overlap by hop. */
    fun stft(samples: FloatArray, fftSize: Int = DEFAULT_FFT, hop: Int = DEFAULT_HOP): Array<SpectralFrame> {
        val window = hann(fftSize)
        val frames = ArrayList<SpectralFrame>()
        var offset = 0
        while (offset + fftSize <= samples.size) {
            // Window + pack into complex input.
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            for (i in 0 until fftSize) {
                real[i] = samples[offset + i] * window[i]
            }
            fft(real, imag, false)
            frames.add(frameFromSpectrum(real, imag))
            offset += hop
        }
        return frames.toTypedArray()
    }

    /** Inverse STFT with overlap-add; result is mono time-domain samples. */
    fun istft(frames: Array<SpectralFrame>, fftSize: Int = DEFAULT_FFT, hop: Int = DEFAULT_HOP): FloatArray {
        if (frames.isEmpty()) return FloatArray(0)
        val window = hann(fftSize)
        val outLen = (frames.size - 1) * hop + fftSize
        val acc = DoubleArray(outLen)
        val wsum = DoubleArray(outLen)

        for ((fi, frame) in frames.withIndex()) {
            val real = DoubleArray(fftSize)
            val imag = DoubleArray(fftSize)
            // Reconstruct half-spectrum to full spectrum (conjugate symmetry).
            val bins = fftSize / 2
            for (k in 0 until bins) {
                val mag = frame.magnitude[k]
                val ph = frame.phase[k]
                val re = mag.toDouble() * cos(ph.toDouble())
                val im = mag.toDouble() * sin(ph.toDouble())
                real[k] = re
                imag[k] = im
                if (k > 0) {
                    real[fftSize - k] = re
                    imag[fftSize - k] = -im
                }
            }
            fft(real, imag, true)
            // Normalize inverse FFT and overlap-add with the synthesis window.
            val base = fi * hop
            for (i in 0 until fftSize) {
                val idx = base + i
                if (idx >= outLen) break
                acc[idx] += (real[i] / fftSize) * window[i]
                wsum[idx] += window[i] * window[i]
            }
        }

        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val w = wsum[i]
            if (w > 1e-8) out[i] = ((acc[i] / w)).coerceIn(-1.0, 1.0).toFloat()
        }
        return out
    }

    /** Hann analysis window (COLA-compatible at 75% overlap). */
    fun hann(size: Int): DoubleArray = DoubleArray(size) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (size - 1))
    }

    /** Convert complex spectrum to magnitude/phase (first half). */
    private fun frameFromSpectrum(real: DoubleArray, imag: DoubleArray): SpectralFrame {
        val bins = real.size / 2
        val mag = FloatArray(bins)
        val phase = FloatArray(bins)
        for (k in 0 until bins) {
            mag[k] = sqrt(real[k] * real[k] + imag[k] * imag[k]).toFloat()
            phase[k] = kotlin.math.atan2(imag[k], real[k]).toFloat()
        }
        return SpectralFrame(mag, phase)
    }

    /** Iterative radix-2 FFT (in place). inverse=true applies the inverse. */
    private fun fft(real: DoubleArray, imag: DoubleArray, inverse: Boolean) {
        val n = real.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two" }

        // Bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1 else -1)
            val wRe = cos(ang)
            val wIm = sin(ang)
            for (i in 0 until n step len) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val u = i + k
                    val v = i + k + half
                    val re = real[v] * curRe - imag[v] * curIm
                    val im = real[v] * curIm + imag[v] * curRe
                    real[v] = real[u] - re
                    imag[v] = imag[u] - im
                    real[u] += re
                    imag[u] += im
                    val nwRe = curRe * wRe - curIm * wIm
                    val nwIm = curRe * wIm + curIm * wRe
                    curRe = nwRe
                    curIm = nwIm
                }
            }
            len = len shl 1
        }
    }

    /**
     * Apply a gain mask (per bin, per frame) to the frames.
     * mask(i, k, freqHz) returns a linear gain for frame i, bin k.
     */
    fun applyMask(
        frames: Array<SpectralFrame>,
        fftSize: Int,
        sampleRate: Int,
        mask: (frameIndex: Int, bin: Int, freqHz: Double) -> Double
    ): Array<SpectralFrame> {
        return frames.mapIndexed { fi, f ->
            val bins = f.magnitude.size
            val m = FloatArray(bins)
            for (k in 0 until bins) {
                val freqHz = k.toDouble() * sampleRate / fftSize
                val gain = mask(fi, k, freqHz)
                m[k] = f.magnitude[k] * gain.toFloat().coerceIn(0f, 10f)
            }
            f.copy(magnitude = m)
        }.toTypedArray()
    }

    /** Convenience: rectangular time-freq mask. Returns 1 (pass) outside, gain inside. */
    fun rectangularMask(
        totalFrames: Int,
        fftSize: Int,
        sampleRate: Int,
        frameStart: Int,
        frameEnd: Int,
        freqMinHz: Double,
        freqMaxHz: Double,
        gain: Double
    ): (Int, Int, Double) -> Double {
        val binMin = (freqMinHz * fftSize / sampleRate).toInt().coerceAtLeast(0)
        val binMax = (freqMaxHz * fftSize / sampleRate).toInt().coerceAtLeast(binMin)
        return { fi, k, _ ->
            if (fi in frameStart until frameEnd && k in binMin..binMax) gain else 1.0
        }
    }
}

/** One STFT frame: magnitude + phase per bin (fftSize/2 bins). */
data class SpectralFrame(
    val magnitude: FloatArray,
    val phase: FloatArray
)