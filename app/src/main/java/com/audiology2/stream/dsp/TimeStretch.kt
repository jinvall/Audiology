package com.audiology2.stream.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline time-domain audio manipulation: linear-interpolation resampling
 * (pitch/speed) and SOLA (synchronized overlap-add) time-stretching that
 * preserves pitch. All functions operate on interleaved float samples.
 */
object TimeStretch {

    /**
     * Resample interleaved audio by [ratio]; ratio > 1 slows down (raises
     * pitch), ratio < 1 speeds up (lowers pitch). Returns a new buffer with
     * `frames * ratio` frames.
     */
    fun resample(samples: FloatArray, channels: Int, ratio: Double): FloatArray {
        if (ratio <= 0.0 || samples.isEmpty() || channels <= 0) return samples
        if (ratio == 1.0) return samples.copyOf()

        val framesIn = samples.size / channels
        val framesOut = (framesIn / ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(framesOut * channels)

        for (c in 0 until channels) {
            var indexFloat = 0.0
            for (o in 0 until framesOut) {
                val i0 = indexFloat.toInt()
                val frac = indexFloat - i0
                val i1 = min(i0 + 1, framesIn - 1)
                val s0 = samples[i0 * channels + c]
                val s1 = samples[i1 * channels + c]
                out[o * channels + c] = (s0 + (s1 - s0) * frac).toFloat()
                indexFloat += ratio
            }
        }
        return out
    }

    /**
     * Time-stretch preserving pitch via SOLA. [ratio] > 1 lengthens the
     * audio (playback becomes slower), ratio < 1 shortens it.
     */
    fun timeStretch(samples: FloatArray, channels: Int, ratio: Double): FloatArray {
        if (ratio <= 0.0 || samples.isEmpty() || channels <= 0) return samples
        if (ratio == 1.0) return samples.copyOf()

        // Analysis frame: ~50ms at 48k. Hop is 1/8 of the window.
        val analysisSize = 2048
        val analysisHop = 256
        val synthesisHop = (analysisHop * ratio).roundToInt().coerceAtLeast(32)
        val searchMax = (analysisHop * 0.5).roundToInt().coerceAtLeast(64)
        val framesIn = samples.size / channels

        // Estimate output size.
        val numFrames = max(1, (framesIn / analysisHop).toInt())
        val outFramesEstimate = numFrames * synthesisHop + analysisSize + 1
        val out = FloatArray(outFramesEstimate * channels)
        var outPos = 0

        // Hann window.
        val window = FloatArray(analysisSize) { i ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * i / (analysisSize - 1))).toFloat()
        }

        // Per-channel work buffers.
        val overlapping = Array(channels) { FloatArray(analysisSize) }

        var readFrame = 0
        var first = true

        while (readFrame < framesIn) {
            val writeStart = outPos

            // 1. Pull the next analysis frame.
            val frame = FloatArray(analysisSize * channels)
            val srcFrames = min(analysisSize, framesIn - readFrame)
            System.arraycopy(samples, readFrame * channels, frame, 0, srcFrames * channels)
            // Apply analysis window (already inside overlap-add of previous? no - we use crossfade approach).
            for (c in 0 until channels) {
                for (i in 0 until srcFrames) {
                    overlapping[c][i] = frame[i * channels + c] * window[i]
                }
                if (srcFrames < analysisSize) {
                    for (i in srcFrames until analysisSize) overlapping[c][i] = 0f
                }
            }

            // 2. Cross-correlate with the tail of the written output to find
            //    the best alignment offset (only after the first frame).
            var bestOffset = 0
            if (!first) {
                var bestScore = Double.POSITIVE_INFINITY
                val searchStart = -searchMax
                val searchEnd = searchMax
                val availOut = min(writeStart, analysisSize)
                for (offset in searchStart..searchEnd) {
                    var score = 0.0
                    val len = availOut
                    for (c in 0 until channels) {
                        val outBase = (writeStart - len) * channels + c
                        var k = 0
                        while (k < len) {
                            val a = out[outBase + k] // written (unwindowed) tail
                            val b = overlapping[c][k]
                            score += abs(a.toDouble() - b.toDouble())
                            k++
                        }
                    }
                    if (score < bestScore) {
                        bestScore = score
                        bestOffset = offset
                    }
                }
            }

            // 3. Crossfade-add the frame into the output at the aligned position.
            val crossLen = min(analysisSize, analysisHop)
            for (c in 0 until channels) {
                val pos = writeStart + bestOffset
                var i = 0
                while (i < analysisSize) {
                    val outIdx = (pos + i) * channels + c
                    if (outIdx >= out.size) break
                    val gain = if (i < crossLen) i.toFloat() / crossLen else 1f
                    val fade = (window[i] * 2f).coerceIn(0f, 1f) * gain
                    out[outIdx] += overlapping[c][i] * fade
                    i++
                }
            }

            outPos = writeStart + synthesisHop
            readFrame += analysisHop
            first = false
        }

        // Trim trailing silence-ish zeros to the estimated extent.
        val finalFrames = (outPos + analysisSize).coerceAtMost(out.size / channels)
        val result = FloatArray(finalFrames * channels)
        System.arraycopy(out, 0, result, 0, result.size)

        // Apply simple RMS-based make-up to compensate crossfade attenuation.
        val peak = result.maxOfOrNull { abs(it) } ?: 0f
        if (peak in 1e-6f..0.98f) {
            val gain = (0.98f / peak).coerceAtMost(1.6f)
            for (i in result.indices) result[i] *= gain
        }
        return result
    }

    /**
     * Pitch-shift by [semitones] while preserving duration, using the
     * resample-then-stretch cascade. Positive = higher pitch.
     */
    fun pitchShift(samples: FloatArray, channels: Int, semitones: Double): FloatArray {
        val pr = Math.pow(2.0, semitones / 12.0)
        if (pr == 1.0) return samples.copyOf()
        val shifted = resample(samples, channels, 1.0 / pr) // raises pitch by pr, shortens
        return timeStretch(shifted, channels, pr)           // restore duration
    }

    /**
     * Full clip transform: applies independent pitch, speed, and stretch.
     * - pitchSemitones: pitch change w/o duration change
     * - speedRatio: combined pitch+duration change (classic tape speed)
     * - stretchRatio: duration change w/o pitch change
     * Returns the processed interleaved samples.
     */
    fun transform(
        samples: FloatArray,
        channels: Int,
        pitchSemitones: Double,
        speedRatio: Double,
        stretchRatio: Double
    ): FloatArray {
        val pr = Math.pow(2.0, pitchSemitones / 12.0)
        val sp = speedRatio.coerceIn(0.25, 4.0)
        val st = stretchRatio.coerceIn(0.25, 4.0)

        var out = samples
        if (pr != 1.0 || sp != 1.0) {
            // Step A: resample by 1/(pr*sp): pitch ×= pr*sp, duration /= pr*sp
            val combined = pr * sp
            if (combined != 1.0) out = resample(out, channels, 1.0 / combined)
            // Step B: stretch by st*pr: duration ×= st*pr → final duration × st/sp
            val rest = st * pr
            if (rest != 1.0) out = timeStretch(out, channels, rest)
        } else if (st != 1.0) {
            out = timeStretch(out, channels, st)
        }
        return out
    }
}
