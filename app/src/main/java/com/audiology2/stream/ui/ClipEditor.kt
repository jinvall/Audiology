package com.audiology2.stream.ui

import android.content.Context
import com.audiology2.stream.audio.AudioBuffer
import com.audiology2.stream.audio.WavUtils
import com.audiology2.stream.dsp.AudioEffects
import com.audiology2.stream.dsp.EffectSettings
import com.audiology2.stream.dsp.TimeStretch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Result of an offline clip edit: the new source file plus the duration
 * change (final audio length in seconds, which may differ after stretch).
 */
data class EditResult(
    val outputFile: File,
    val finalDurationSec: Double
)

/**
 * Offline clip editing: reads the clip's source audio window, applies pitch/
 * speed/stretch or an effects chain, and writes a new WAV that the clip
 * references. All heavy work runs on [Dispatchers.IO].
 */
class ClipEditor(private val context: Context) {

    private val editDir: File
        get() = File(context.filesDir, "edited").apply { mkdirs() }

    /** Read the current audio window of a clip (sourceOffset..sourceOffset+duration). */
    suspend fun readClipAudio(clip: Clip): AudioBuffer? = withContext(Dispatchers.IO) {
        val file = File(clip.sourcePath)
        if (!file.exists()) null
        else WavUtils.readRange(file, clip.sourceOffset, clip.sourceOffset + clip.duration)
    }

    /**
     * Apply pitch/speed/stretch to the clip's audio and create a new source
     * file. Returns [EditResult] or null on failure.
     */
    suspend fun applyPitchSpeed(
        clip: Clip,
        pitchSemitones: Double,
        speedRatio: Double,
        stretchRatio: Double,
        preview: Boolean = false
    ): EditResult? = withContext(Dispatchers.IO) {
        val src = readClipAudio(clip) ?: return@withContext null
        if (src.frames == 0) return@withContext null

        val processed = TimeStretch.transform(
            src.samples, src.channels, pitchSemitones, speedRatio, stretchRatio
        )
        val finalFrames = processed.size / src.channels
        val buf = AudioBuffer(processed, src.sampleRate, src.channels, finalFrames)

        val name = buildString {
            append(clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_"))
            if (preview) append("_preview") else {
                if (pitchSemitones != 0.0) append("_p${pitchSemitones}st")
                if (speedRatio != 1.0) append("_s${speedRatio}")
                if (stretchRatio != 1.0) append("_t${stretchRatio}")
            }
        }
        val out = File(editDir, "${name}_${UUID.randomUUID().toString().take(8)}.wav")
        if (!WavUtils.write(out, buf)) return@withContext null
        EditResult(out, finalFrames.toDouble() / src.sampleRate)
    }

    /** Apply the effects chain to the clip's audio. */
    suspend fun applyEffects(
        clip: Clip,
        settings: EffectSettings,
        preview: Boolean = false
    ): EditResult? = withContext(Dispatchers.IO) {
        val src = readClipAudio(clip) ?: return@withContext null
        if (src.frames == 0) return@withContext null

        val processed = AudioEffects.apply(src.samples, src.sampleRate, src.channels, settings)
        val buf = AudioBuffer(processed, src.sampleRate, src.channels, processed.size / src.channels)
        val name = buildString {
            append(clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_"))
            if (preview) append("_fx_preview")
            else append("_fx")
        }
        val out = File(editDir, "${name}_${UUID.randomUUID().toString().take(8)}.wav")
        if (!WavUtils.write(out, buf)) return@withContext null
        EditResult(out, buf.frames.toDouble() / buf.sampleRate)
    }

    /**
     * Apply a transform to only a sub-range of a clip (clip-relative seconds)
     * and produce the replacement clips (head, processed middle, tail).
     * Empty head/tail are omitted. Returns null when the range is invalid or
     * the source cannot be read.
     */
    suspend fun applyRangeTransform(
        clip: Clip,
        rangeRel: Pair<Double, Double>,
        transform: (FloatArray, Int, Int) -> FloatArray
    ): List<Clip>? = withContext(Dispatchers.IO) {
        val rStart = rangeRel.first.coerceIn(0.0, clip.duration)
        val rEnd = rangeRel.second.coerceIn(0.0, clip.duration)
        if (rEnd <= rStart + 0.001) return@withContext null // degenerate

        val src = readClipAudio(clip) ?: return@withContext null
        if (src.frames == 0) return@withContext null

        val sr = src.sampleRate
        val ch = src.channels
        val headFrames = (rStart * sr).toInt().coerceIn(0, src.frames)
        val midFrames = ((rEnd - rStart) * sr).toInt().coerceIn(1, src.frames - headFrames)
        val tailFrames = (src.frames - headFrames - midFrames).coerceAtLeast(0)

        // Extract the middle section and process it.
        val midSamples = FloatArray(midFrames * ch)
        System.arraycopy(src.samples, headFrames * ch, midSamples, 0, midSamples.size)
        val processed = transform(midSamples, sr, ch)
        val processedFrames = (processed.size / ch).coerceAtLeast(1)

        // Paths for output files.
        fun outFile(tag: String): File {
            val name = clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            return File(editDir, "${name}_${tag}_${UUID.randomUUID().toString().take(8)}.wav")
        }

        val result = mutableListOf<Clip>()
        val baseStart = clip.startTime

        // Head: untouched region before the range.
        if (headFrames > 0) {
            result.add(
                clip.with(
                    id = UUID.randomUUID().toString(),
                    name = "${clip.name} (H)",
                    sourceOffset = clip.sourceOffset,
                    duration = headFrames.toDouble() / sr,
                    startTime = baseStart
                ).copy(gain = clip.gain)
            )
        }

        // Processed middle.
        val midBuf = AudioBuffer(processed, sr, ch, processedFrames)
        val midFile = outFile("m")
        if (!WavUtils.write(midFile, midBuf)) return@withContext null
        result.add(
            clip.with(
                id = UUID.randomUUID().toString(),
                name = clip.name,
                sourcePath = midFile.absolutePath,
                sourceOffset = 0.0,
                duration = processedFrames.toDouble() / sr,
                startTime = baseStart + rStart
            ).copy(gain = clip.gain)
        )

        // Tail: untouched region after the range (shifted by duration change).
        if (tailFrames > 0) {
            result.add(
                clip.with(
                    id = UUID.randomUUID().toString(),
                    name = "${clip.name} (T)",
                    sourceOffset = clip.sourceOffset + rEnd,
                    duration = tailFrames.toDouble() / sr,
                    startTime = baseStart + rStart + processedFrames.toDouble() / sr
                ).copy(gain = clip.gain)
            )
        }
        result
    }

    /** Delete a previously generated preview/temp file, if it is inside our edit dir. */
    fun deleteGenerated(file: File) {
        val base = editDir.canonicalPath
        val target = file.canonicalPath
        if (target.startsWith(base) && file.exists()) file.delete()
    }
}
