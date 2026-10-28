package com.audiology2.stream.ui

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents an audio clip on a track.
 * A clip references a portion of an audio file.
 */
@Serializable
data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Clip",
    /** Source file path or content URI */
    val sourcePath: String,
    /** Start offset in source file (seconds) */
    var sourceOffset: Double = 0.0,
    /** Duration of this clip (seconds) */
    var duration: Double,
    /** Start time on track timeline (seconds) */
    var startTime: Double = 0.0,
    /** Track ID this clip belongs to */
    var trackId: String = "",
    /** Gain multiplier (1.0 = unity) */
    var gain: Float = 1.0f,
    /** Pan (-1.0 left, 0 center, 1.0 right) */
    var pan: Float = 0.0f,
    /** Mute state */
    var muted: Boolean = false,
    /** Solo state (handled at track level usually) */
    var solo: Boolean = false,
    /** Color for waveform display */
    val color: Int = 0xFF00D4AA.toInt(),
    /** Fade in duration (seconds) */
    var fadeIn: Double = 0.0,
    /** Fade out duration (seconds) */
    var fadeOut: Double = 0.0,
    /** Locked - prevents accidental edits */
    var locked: Boolean = false
) {

    /** End time on track */
    val endTime: Double get() = startTime + duration

    /** Create a modified copy (renamed to avoid conflict with data class copy) */
    fun with(
        id: String = this.id,
        name: String = this.name,
        sourcePath: String = this.sourcePath,
        sourceOffset: Double = this.sourceOffset,
        duration: Double = this.duration,
        startTime: Double = this.startTime,
        trackId: String = this.trackId,
        gain: Float = this.gain,
        pan: Float = this.pan,
        muted: Boolean = this.muted,
        solo: Boolean = this.solo,
        color: Int = this.color,
        fadeIn: Double = this.fadeIn,
        fadeOut: Double = this.fadeOut,
        locked: Boolean = this.locked
    ): Clip = Clip(
        id = id, name = name, sourcePath = sourcePath, sourceOffset = sourceOffset,
        duration = duration, startTime = startTime, trackId = trackId, gain = gain,
        pan = pan, muted = muted, solo = solo, color = color, fadeIn = fadeIn,
        fadeOut = fadeOut, locked = locked
    )

    /** Split this clip at the given time (relative to clip start) */
    fun splitAt(relativeTime: Double): Pair<Clip, Clip>? {
        if (relativeTime <= 0 || relativeTime >= duration) return null
        val left = with(
            id = UUID.randomUUID().toString(),
            name = "$name (L)",
            duration = relativeTime
        )
        val right = with(
            id = UUID.randomUUID().toString(),
            name = "$name (R)",
            sourceOffset = sourceOffset + relativeTime,
            duration = duration - relativeTime,
            startTime = startTime + relativeTime
        )
        return Pair(left, right)
    }
}

/**
 * Audio track containing clips.
 */
@Serializable
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Track",
    /** Clips on this track, sorted by startTime */
    val clips: MutableList<Clip> = mutableListOf(),
    /** Track gain (1.0 = unity) */
    var gain: Float = 1.0f,
    /** Track pan (-1..1) */
    var pan: Float = 0.0f,
    /** Mute track */
    var muted: Boolean = false,
    /** Solo track */
    var solo: Boolean = false,
    /** Track color for UI */
    val color: Int = 0xFF00D4AA.toInt(),
    /** Track height in UI (relative) */
    var height: Float = 1.0f,
    /** Record armed */
    var armed: Boolean = false,
    /** Input source for recording */
    var inputSource: String = "DEFAULT",
    /** Monitor input while recording */
    var monitor: Boolean = false
) {

    /** Total duration of track based on clips */
    val duration: Double get() = clips.maxOfOrNull { it.endTime } ?: 0.0

    /** Add clip at end of track */
    fun appendClip(clip: Clip) {
        val newClip = clip.with(trackId = id, startTime = duration)
        clips.add(newClip)
        clips.sortBy { it.startTime }
    }

    /** Insert clip at specific time */
    fun insertClip(clip: Clip, time: Double) {
        val newClip = clip.with(trackId = id, startTime = time)
        clips.add(newClip)
        clips.sortBy { it.startTime }
    }

    /** Remove clip */
    fun removeClip(clipId: String): Boolean {
        val initialSize = clips.size
        clips.removeAll { it.id == clipId }
        return clips.size < initialSize
    }

    /** Get clip at time */
    fun getClipAt(time: Double): Clip? {
        return clips.firstOrNull { it.startTime <= time && time < it.endTime }
    }

    /** Get all clips overlapping time range */
    fun getClipsInRange(start: Double, end: Double): List<Clip> {
        return clips.filter { it.startTime < end && it.endTime > start }
    }
}

/**
 * Multi-track project/session.
 */
@Serializable
data class AudioProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled Project",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val tracks: MutableList<Track> = mutableListOf(),
    /** Master track settings */
    var masterGain: Float = 1.0f,
    /** Tempo (BPM) for grid */
    var tempo: Double = 120.0,
    /** Time signature */
    var timeSignatureNumerator: Int = 4,
    var timeSignatureDenominator: Int = 4,
    /** Project folder for audio files */
    var projectFolder: String = ""
) {

    /** Total project duration */
    val duration: Double get() = tracks.maxOfOrNull { it.duration } ?: 0.0

    /** Add new track */
    fun addTrack(): Track {
        val track = Track()
        tracks.add(track)
        return track
    }

/** Remove track */
    fun removeTrack(trackId: String): Boolean {
        val initialSize = tracks.size
        tracks.removeAll { it.id == trackId }
        return tracks.size < initialSize
    }

    /** Get track by ID */
    fun getTrack(trackId: String): Track? = tracks.firstOrNull { it.id == trackId }

    /** Move track up/down in list */
    fun moveTrack(trackId: String, delta: Int) {
        val idx = tracks.indexOfFirst { it.id == trackId }
        if (idx >= 0) {
            val newIdx = (idx + delta).coerceIn(0, tracks.size - 1)
            if (newIdx != idx) {
                tracks.add(newIdx, tracks.removeAt(idx))
            }
        }
    }
}

/**
 * Project manager - handles saving/loading projects.
 */
object ProjectManager {
    private const val PROJECTS_KEY = "audio_projects"
    private const val CURRENT_PROJECT_KEY = "current_project"

    // TODO: Implement with DataStore/Room for persistence
    // For now, in-memory only
    private var currentProject: AudioProject? = null

    fun getCurrentProject(): AudioProject? = currentProject

    fun setCurrentProject(project: AudioProject?) {
        currentProject = project
        // TODO: Save to DataStore
    }

    fun createNewProject(name: String = "New Project"): AudioProject {
        val project = AudioProject(name = name)
        setCurrentProject(project)
        return project
    }
}