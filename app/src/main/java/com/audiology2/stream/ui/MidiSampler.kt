package com.audiology2.stream.ui

import android.content.Context
import com.audiology2.stream.audio.AudioBuffer
import com.audiology2.stream.audio.WavUtils
import com.audiology2.stream.dsp.TimeStretch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Renders [MidiClip]s to audio by triggering the track's sample pool
 * (audio clips) at the correct tempo, pitch-shifting for melodic use.
 *
 * Drum mode: samples are one-shots mapped by pitch offset from
 * [MidiClip.drumRootPitch]. Melodic mode: the first sample is the base
 * tone and notes pitch-shift it by semitone offset.
 */
class MidiSampler(private val context: Context) {

    private val renderDir: File
        get() = File(context.cacheDir, "midi_render").apply { mkdirs() }

    /** Sample pool (audio clips on the same track, ordered by start time). */
    private suspend fun readSamplePool(sources: List<Clip>): List<AudioBuffer?> =
        sources.map { clip ->
            val f = File(clip.sourcePath)
            if (!f.exists()) null
            else com.audiology2.stream.audio.WavUtils.readRange(f, clip.sourceOffset, clip.sourceOffset + clip.duration)
        }

    /**
     * Render a MIDI clip into a WAV file. Returns null on failure.
     * [voices] maps a pitch to its sample (one buffer per pitch); the caller
     * passes the track's pool and mode handling happens here.
     */
    suspend fun renderClip(
        clip: MidiClip,
        sources: List<Clip>,
        tempo: Double,
        sampleRate: Int = 48000
    ): File? = withContext(Dispatchers.IO) {
        if (clip.notes.isEmpty() || sources.isEmpty()) return@withContext null
        val bpm = tempo.coerceIn(40.0, 300.0)
        val beatSec = 60.0 / bpm

        val pool = readSamplePool(sources).filterNotNull()
        if (pool.isEmpty()) return@withContext null

        val totalFrames = ((clip.endTick.toDouble() / clip.ticksPerBeat) * beatSec * sampleRate)
            .toInt().coerceAtLeast(0)
        if (totalFrames == 0) return@withContext null

        // Mono render buffer; the mixer/player handles channel count.
        val out = FloatArray(totalFrames)

        for (note in clip.notes) {
            val onsetSec = clip.timeAt(note.startTick, bpm)
            val endSec = clip.timeAt(note.endTick, bpm)
            val startFrame = (onsetSec * sampleRate).toInt().coerceIn(0, totalFrames)
            val endFrame = (endSec * sampleRate).toInt().coerceIn(startFrame, totalFrames)
            if (endFrame <= startFrame) continue

            // Select and (melodic mode) pitch-shift the voice.
            var voice = if (clip.drumMode) {
                pool[((note.pitch - clip.drumRootPitch) % pool.size + pool.size) % pool.size]
            } else {
                val basePitch = clip.notes.minOf { it.pitch }
                val semitones = note.pitch - basePitch
                if (semitones == 0) pool[0]
                else {
                    val shifted = TimeStretch.pitchShift(pool[0].samples, pool[0].channels, semitones.toDouble())
                    AudioBuffer(shifted, pool[0].sampleRate, pool[0].channels, shifted.size / pool[0].channels)
                }
            }

            // Align the voice to the render sample rate.
            if (voice.sampleRate != sampleRate) {
                val ratio = voice.sampleRate.toDouble() / sampleRate
                val resampled = TimeStretch.resample(voice.samples, voice.channels, ratio)
                voice = AudioBuffer(resampled, sampleRate, voice.channels, resampled.size / voice.channels)
            }

            // Velocity + fast envelope to gate the sample at note end.
            val velGain = note.velocity.coerceIn(1, 127) / 127f
            val attackFrames = (sampleRate * 0.002).toInt().coerceAtLeast(4)
            val releaseFrames = (sampleRate * 0.008).toInt().coerceAtLeast(8)

            var src = 0
            val srcFrames = voice.frames
            for (f in startFrame until endFrame) {
                if (src >= srcFrames) break
                var s = 0f
                for (c in 0 until voice.channels) {
                    s += voice.samples[src * voice.channels + c]
                }
                s /= voice.channels
                // Envelope
                val local = f - startFrame
                val g = when {
                    local < attackFrames -> local.toFloat() / attackFrames
                    local > (endFrame - startFrame - releaseFrames) -> (endFrame - startFrame - local).toFloat() / releaseFrames
                    else -> 1f
                }
                val v = (s * (g.coerceIn(0f, 1f)) * velGain)
                out[f] = (out[f] + v).coerceIn(-1f, 1f)
                src++
            }
        }

        // Normalize to a healthy peak.
        var peak = 0f
        for (s in out) { val a = kotlin.math.abs(s); if (a > peak) peak = a }
        if (peak in 1e-6f..0.05f) {
            val g = 0.05f / peak
            for (i in out.indices) out[i] *= g
        }

        val name = clip.name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val file = File(renderDir, "${name}_${UUID.randomUUID().toString().take(8)}.wav")
        val buf = AudioBuffer(out, sampleRate, 1, totalFrames)
        if (!WavUtils.write(file, buf)) return@withContext null
        file
    }

    /**
     * Render every MIDI clip in the project to audio and return a project
     * where each MIDI clip is replaced by a rendered audio clip on its track.
     * Returns the original project when there is nothing to render.
     */
    suspend fun renderProject(project: AudioProject): AudioProject = withContext(Dispatchers.IO) {
        val hasMidi = project.tracks.any { it.midiClips.isNotEmpty() }
        if (!hasMidi) return@withContext project

        var work = project
        for (track in project.tracks) {
            if (track.midiClips.isEmpty()) continue
            for (mc in track.midiClips) {
                val sources = track.clips
                val sampleRate = project.sampleRate
                val rendered = renderClip(mc, sources, project.tempo, sampleRate)
                if (rendered != null) {
                    val durationSec = (mc.endTick.toDouble() / mc.ticksPerBeat * (60.0 / project.tempo))
                    val audioClip = Clip(
                        name = "${mc.name} (rendered)",
                        sourcePath = rendered.absolutePath,
                        duration = durationSec,
                        startTime = mc.startTime,
                        trackId = track.id,
                        color = mc.color
                    )
                    val updatedTrack = track.withClips(track.clips + audioClip)
                    work = work.updateTrack(updatedTrack)
                }
            }
        }
        work.copy(tracks = work.tracks.map { t ->
            if (t.midiClips.isNotEmpty()) t.copy(midiClips = emptyList()) else t
        })
    }
}
