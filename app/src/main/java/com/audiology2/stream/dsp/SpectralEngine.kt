package com.audiology2.stream.dsp

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spectral analysis tools on top of a magnitude spectrum frame
 * (fftSize/2 bins, normalized 0..1 as produced by [FFTProcessor]).
 *
 * Pure Kotlin, no Android deps - unit testable. Powers the Spectral Tools
 * card: live tuning, partial tracking, and measurements.
 */
object SpectralEngine {

    /** Convert the normalized dB magnitude (0..1 from FFTProcessor) back to an approximate linear amplitude. */
    fun linearMagnitude(normalized: Float): Double {
        val db = normalized.coerceIn(0f, 1f) * 120.0 - 120.0
        return 10.0.pow(db / 20.0)
    }

    /** Frequency of a bin index at the given sample rate / FFT size. */
    fun binFreq(bin: Int, sampleRate: Int, fftSize: Int): Double =
        bin.toDouble() * sampleRate / fftSize

    /** Loudest single bin (peak) in the frame. Returns null for silence. */
    fun peak(frame: FloatArray, sampleRate: Int, fftSize: Int): SpectralPeak? {
        if (frame.isEmpty()) return null
        // Skip DC and near-DC (first 2 bins).
        var best = 2
        var bestMag = frame.getOrElse(2) { 0f }
        for (i in 3 until frame.size) {
            val m = frame[i]
            if (m > bestMag) {
                bestMag = m
                best = i
            }
        }
        if (bestMag < 0.01f) return null
        // Parabolic interpolation of the peak bin for sub-bin frequency.
        val prev = frame.getOrElse(best - 1) { 0f }.toDouble()
        val cur = bestMag.toDouble()
        val next = frame.getOrElse(best + 1) { 0f }.toDouble()
        val shift = if (cur > 0) 0.5 * (prev - next) / (prev - 2 * cur + next) else 0.0
        val freq = binFreq(best, sampleRate, fftSize) + shift * (sampleRate.toDouble() / fftSize)
        val db = if (bestMag > 1e-5f) 20.0 * log10(linearMagnitude(bestMag)) else -120.0
        return SpectralPeak(freq.coerceAtLeast(20.0), db, bestMag.toDouble(), best)
    }

    /**
     * Estimate the fundamental using Harmonic Product Spectrum on the
     * linear magnitude spectrum. Returns Hz or null for weak/unpitched audio.
     */
    fun fundamental(frame: FloatArray, sampleRate: Int, fftSize: Int): Double? {
        if (frame.isEmpty()) return null
        val n = frame.size
        val linear = FloatArray(n) { linearMagnitude(frame[it]).toFloat() }
        val harmonics = 5
        val hps = FloatArray(n)
        // Multiply downsampled spectra (harmonic product) - relative values.
        for (i in 0 until n / harmonics) {
            var p = 1f
            for (h in 1..harmonics) {
                p *= linear[i * h].coerceAtLeast(0f)
            }
            hps[i] = p
        }
        // Normalize by the running max so absolute input level doesn't matter.
        var runningMax = 1e-9f
        for (i in 0 until n) {
            if (hps[i] > runningMax) runningMax = hps[i]
        }
        // Find peak above 40Hz (bin >= 40 * fftSize / sr), below ~2k for f0.
        val minBin = (40.0 * fftSize / sampleRate).roundToInt().coerceAtLeast(2)
        val maxBin = (2000.0 * fftSize / sampleRate).roundToInt().coerceAtMost(n - 1)
        var best = minBin
        var bestVal = 0f
        for (i in minBin + 1..maxBin) {
            val rel = hps[i] / runningMax
            if (rel > bestVal) {
                bestVal = rel
                best = i
            }
        }
        if (bestVal < 0.05f) return null // weak/ambiguous peak
        return binFreq(best, sampleRate, fftSize)
    }

    /** N strongest partials (sorted peak bins, minimum distance apart). */
    fun partials(frame: FloatArray, sampleRate: Int, fftSize: Int, topN: Int = 8, minGapBins: Int = 4): List<SpectralPeak> {
        val peaks = mutableListOf<SpectralPeak>()
        val n = frame.size
        val used = BooleanArray(n)
        repeat(topN) {
            var best = -1
            var bestMag = 0f
            for (i in 2 until n) {
                if (!used[i] && frame[i] > bestMag) {
                    bestMag = frame[i]
                    best = i
                }
            }
            if (best < 0 || bestMag < 0.02f) return@repeat
            val p = SpectralPeak(binFreq(best, sampleRate, fftSize), 0.0, bestMag.toDouble(), best)
            peaks.add(p)
            for (i in (best - minGapBins).coerceAtLeast(0)..(best + minGapBins).coerceAtMost(n - 1)) {
                used[i] = true
            }
        }
        return peaks.sortedBy { it.freq }
    }

    /** Spectral centroid in Hz (brightness). */
    fun centroid(frame: FloatArray, sampleRate: Int, fftSize: Int): Double {
        var num = 0.0
        var den = 0.0
        for (i in 2 until frame.size) {
            val m = linearMagnitude(frame[i])
            num += binFreq(i, sampleRate, fftSize) * m
            den += m
        }
        return if (den > 1e-9) num / den else 0.0
    }

    /** Frequency where cumulative energy crosses [percent] (default 50%). */
    fun rolloff(frame: FloatArray, sampleRate: Int, fftSize: Int, percent: Double = 0.85): Double {
        val linear = FloatArray(frame.size) { linearMagnitude(frame[it]).toFloat() }
        val total = linear.sum().coerceAtLeast(1e-12f)
        var acc = 0f
        for (i in 2 until linear.size) {
            acc += linear[i]
            if (acc / total >= percent) return binFreq(i, sampleRate, fftSize)
        }
        return 0.0
    }

    /** Convert a frequency to note name, MIDI number, and cents deviation. */
    fun noteFromFreq(freq: Double): NoteInfo {
        val midi = (69.0 + 12.0 * (log10(freq / 440.0) / log10(2.0))).roundToInt()
        val clamped = midi.coerceIn(0, 127)
        val exact = 440.0 * 2.0.pow((clamped - 69) / 12.0)
        val cents = (1200.0 * (log10(freq / exact) / log10(2.0))).roundToInt()
        return NoteInfo(clamped, noteNames[clamped % 12] + ((clamped / 12) - 1), cents)
    }

    /** Mid-side = -1..1 tuner offset in cents, with the note's frequency. */
    private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** RMS of a time-domain frame (for loudness readout). */
    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var acc = 0.0
        for (s in samples) acc += s.toDouble() * s
        return sqrt(acc / samples.size)
    }

    /** Map a linear amplitude to approximate dBFS. */
    fun ampToDb(amp: Double): Double =
        if (amp > 1e-7) 20.0 * log10(amp) else -140.0
}

/** Peak in a spectrum frame. */
data class SpectralPeak(
    val freq: Double,
    val db: Double,
    val magnitude: Double,
    val bin: Int = 0
)

/** Note identification result for the tuner. */
data class NoteInfo(
    val midi: Int,
    val name: String,
    val cents: Int
)