package com.audiology2.stream.ui

import android.content.Context
import com.audiology2.stream.audio.AudioBuffer
import com.audiology2.stream.audio.WavUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Result of creating a musical sample from a clip/recording. */
data class SampleResult(
    val sampleFile: File,
    val durationSec: Double,
    /** Detected loop length in seconds (0 = no loop detected). */
    val loopLengthSec: Double,
    /** Start time placed on the beats track (beat-snapped). */
    val placedAt: Double
)

/**
 * Turns a recorded or selected clip into a beat-ready tonal sample:
 * silence trim, fade edges, normalization, optional BPM-synced loop
 * detection, then placement on the "Beats" track at the next measure.
 */
class SampleMaker(private val context: Context) {

    private val samplesDir: File
        get() = File(context.filesDir, "samples").apply { mkdirs() }

    /**
     * Create a sample from a clip and add it to the project's Beats track.
     * Returns null when the source cannot be read.
     */
    suspend fun makeSample(
        clip: Clip,
        project: AudioProject,
        thresholdDb: Double = -52.0,
        normalize: Boolean = true,
        snapToBeat: Boolean = true
    ): SampleResult? = withContext(Dispatchers.IO) {
        val src = readClipSource(clip) ?: return@withContext null
        if (src.frames == 0) return@withContext null

        // 1. Trim silence at both ends.
        val mix = src.mixdown()
        val threshold = Math.pow(10.0, thresholdDb / 20.0).toFloat()
        val window = (src.sampleRate / 50).coerceAtLeast(64) // 20ms windows
        val startFrame = WavUtils.findFirstNonSilent(mix, threshold, window)
        val endFrame = WavUtils.findLastNonSilent(mix, threshold, window)
        val trimmedStart = startFrame
        var frameCount = (endFrame - startFrame).coerceAtLeast(0)

        // 2. Detect a BPM-synced loop: strongly periodic audio such as a
        //    voice/percussive one-shot. Use the project tempo to find the
        //    total sample length in beats or bars, clamped to the audio length.
        var loopLengthSec = 0.0
        val beatSec = 60.0 / project.tempo.coerceIn(40.0, 300.0)
        if (snapToBeat && frameCount > 0) {
            val audio = frameCount.toDouble() / src.sampleRate
            // Try whole numbers of beats: 1, 2, 4 beats; pick the largest that fits.
            for (beats in listOf(4, 2, 1)) {
                val len = beats * beatSec
                if (len <= audio * 1.05) { // allow tiny overshoot
                    loopLengthSec = len
                    frameCount = (len * src.sampleRate).toInt().coerceAtMost(frameCount)
                    break
                }
            }
        }

        // 3. Slice the trimmed audio.
        val outFrames = frameCount.coerceAtLeast(1)
        val out = FloatArray(outFrames * src.channels)
        val srcStart = trimmedStart * src.channels
        var copyLen = (outFrames * src.channels).coerceAtMost(src.samples.size - srcStart)
        if (copyLen <= 0) copyLen = 0
        if (copyLen > 0) {
            System.arraycopy(src.samples, srcStart, out, 0, copyLen)
        }

        // 4. Edge fades (3ms) to avoid clicks.
        val fadeFrames = (src.sampleRate * 0.003).toInt().coerceAtLeast(1)
        for (i in 0 until fadeFrames.coerceAtMost(outFrames)) {
            val g = i.toFloat() / fadeFrames
            for (c in 0 until src.channels) {
                val idx = i * src.channels + c
                if (idx < out.size) out[idx] *= g
            }
        }
        for (i in 0 until fadeFrames.coerceAtMost(outFrames)) {
            val g = i.toFloat() / fadeFrames
            for (c in 0 until src.channels) {
                val idx = (outFrames - 1 - i) * src.channels + c
                if (idx in 0 until out.size) out[idx] *= g
            }
        }

        // 5. Normalize.
        val final = if (normalize) WavUtils.normalize(out) else out

        // 6. Write sample WAV.
        val safeName = clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val file = File(samplesDir, "${safeName}_${UUID.randomUUID().toString().take(8)}.wav")
        val buf = AudioBuffer(final, src.sampleRate, src.channels, outFrames)
        if (!WavUtils.write(file, buf)) return@withContext null

        // 7. Place on the Beats track at the next measure boundary.
        val durationSec = outFrames.toDouble() / src.sampleRate
        val beatSec2 = 60.0 / project.tempo.coerceIn(40.0, 300.0)
        val measureSec = beatSec2 * project.timeSignatureNumerator
        val durationSec2 = project.duration
        val placed = if (durationSec2 <= 0.0) 0.0 else {
            val nextMeasure = kotlin.math.ceil(durationSec2 / measureSec) * measureSec
            if (nextMeasure > durationSec2) nextMeasure else durationSec2
        }

        SampleResult(file, durationSec, loopLengthSec, placed)
    }

    private suspend fun readClipSource(clip: Clip): AudioBuffer? {
        val file = File(clip.sourcePath)
        if (!file.exists()) return null
        return WavUtils.readRange(file, clip.sourceOffset, clip.sourceOffset + clip.duration)
    }
}
