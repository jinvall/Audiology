package com.audiology2.stream.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Represents an audio clip on a track.
 * A clip references a portion of an audio file.
 * All playback-affecting properties are mutable `var`s so realtime
 * drag/resize/edit operations can update in place; serialization uses
 * the default snapshot values plus the explicitly stored lists.
 */
@Serializable
data class Clip(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Clip",
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
    var locked: Boolean = false,
    /** Pitch shift in semitones (-12..+12, 0 = unchanged). Applied at playback/export. */
    var pitchSemitones: Double = 0.0,
    /** Playback speed ratio (1.0 = normal, 0.5 = half speed, 2.0 = double). */
    var speedRatio: Double = 1.0,
    /** Independent time-stretch ratio (1.0 = normal). When != speedRatio, formant-preserving stretch is used. */
    var stretchRatio: Double = 1.0
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
        locked: Boolean = this.locked,
        pitchSemitones: Double = this.pitchSemitones,
        speedRatio: Double = this.speedRatio,
        stretchRatio: Double = this.stretchRatio
    ): Clip = Clip(
        id = id, name = name, sourcePath = sourcePath, sourceOffset = sourceOffset,
        duration = duration, startTime = startTime, trackId = trackId, gain = gain,
        pan = pan, muted = muted, solo = solo, color = color, fadeIn = fadeIn,
        fadeOut = fadeOut, locked = locked, pitchSemitones = pitchSemitones,
        speedRatio = speedRatio, stretchRatio = stretchRatio
    )

    /** Split this clip at the given time (relative to clip start). Returns null when unusable. */
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
    var name: String = "Track",
    /** Clips on this track, sorted by startTime */
    val clips: List<Clip> = emptyList(),
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

    fun withClips(newClips: List<Clip>): Track = copy(clips = newClips.sortedBy { it.startTime })

    /** Add clip at end of track */
    fun appendClip(clip: Clip): Track = withClips(clips + clip.with(trackId = id, startTime = duration))

    /** Insert clip at specific time */
    fun insertClip(clip: Clip, time: Double): Track = withClips(clips + clip.with(trackId = id, startTime = time))

    /** Remove clip */
    fun removeClip(clipId: String): Track = withClips(clips.filterNot { it.id == clipId })

    /** Replace a clip (by id) with another, e.g. after drag/resize/edit. */
    fun updateClip(updated: Clip): Track = withClips(clips.map { if (it.id == updated.id) updated else it })

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
    var name: String = "Untitled Project",
    val createdAt: Long = System.currentTimeMillis(),
    var modifiedAt: Long = System.currentTimeMillis(),
    val sampleRate: Int = 48000,
    val bitDepth: Int = 24,
    val tracks: List<Track> = emptyList(),
    /** Master track settings */
    var masterGain: Float = 1.0f,
    /** Tempo (BPM) for grid */
    var tempo: Double = 120.0,
    /** Time signature */
    var timeSignatureNumerator: Int = 4,
    var timeSignatureDenominator: Int = 4,
    /** Project folder for audio files */
    var projectFolder: String = "",
    /** Loop region start/end (seconds); end 0 means no loop. */
    var loopStart: Double = 0.0,
    var loopEnd: Double = 0.0
) {

    /** Total project duration */
    val duration: Double get() = tracks.maxOfOrNull { it.duration } ?: 0.0

    fun withTracks(newTracks: List<Track>): AudioProject = copy(tracks = newTracks).touch()

    private fun touch(): AudioProject { modifiedAt = System.currentTimeMillis(); return this }

    /** Add new track */
    fun addTrack(name: String? = null, color: Int = 0xFF00D4AA.toInt()): AudioProject {
        val n = tracks.size + 1
        val track = Track(name = name ?: "Track $n", color = color)
        return withTracks(tracks + track)
    }

    /** Remove track */
    fun removeTrack(trackId: String): AudioProject = withTracks(tracks.filterNot { it.id == trackId })

    /** Replace a track (by id). */
    fun updateTrack(updated: Track): AudioProject = withTracks(tracks.map { if (it.id == updated.id) updated else it })

    /** Get track by ID */
    fun getTrack(trackId: String): Track? = tracks.firstOrNull { it.id == trackId }

    /** Move track up/down in list */
    fun moveTrack(trackId: String, delta: Int): AudioProject {
        val idx = tracks.indexOfFirst { it.id == trackId }
        if (idx < 0) return this
        val newIdx = (idx + delta).coerceIn(0, tracks.size - 1)
        if (newIdx == idx) return this
        val mutable = tracks.toMutableList()
        mutable.add(newIdx, mutable.removeAt(idx))
        return withTracks(mutable)
    }

    /** Replace clips on a track (by track id). Returns new project or this if track missing. */
    fun updateTrackClips(trackId: String, newClips: List<Clip>): AudioProject {
        val track = getTrack(trackId) ?: return this
        return updateTrack(track.withClips(newClips))
    }
}

/**
 * Project manager - handles saving/loading projects.
 *
 * Sync API backed by an in-memory cache so the UI can mutate and re-render
 * immediately; every change is persisted to DataStore in the background.
 */
object ProjectManager {
    private const val PROJECTS_KEY = "audio_projects"
    private const val CURRENT_PROJECT_KEY = "current_project"

    @Volatile
    private var loaded = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val PROJECTS_PREF_KEY = stringPreferencesKey(PROJECTS_KEY)
    private val CURRENT_PROJECT_PREF_KEY = stringPreferencesKey(CURRENT_PROJECT_KEY)

    // Private scope for background persistence writes.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val editorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** In-memory project list cache. */
    private var projects: List<AudioProject> = emptyList()

    /** In-memory current project id. */
    private var currentProjectId: String? = null

    private val dataStore: DataStore<Preferences>
        get() = com.audiology2.stream.config.SettingsStore.getSharedDataStore()

    /** Flow of saved projects from DataStore. */
    val projectsFlow: Flow<List<AudioProject>>
        get() = dataStore.data.map { prefs ->
            val raw = prefs[PROJECTS_PREF_KEY]
            if (raw.isNullOrEmpty()) emptyList()
            else runCatching { json.decodeFromString<List<AudioProject>>(raw) }.getOrDefault(emptyList())
        }

    /** Flow of current project from DataStore. */
    val currentProjectFlow: Flow<AudioProject?>
        get() = dataStore.data.map { prefs ->
            val projectId = prefs[CURRENT_PROJECT_PREF_KEY] ?: return@map null
            val raw = prefs[PROJECTS_PREF_KEY]
            if (raw.isNullOrEmpty()) null
            else runCatching {
                json.decodeFromString<List<AudioProject>>(raw).find { it.id == projectId }
            }.getOrDefault(null)
        }

    /** Load the persisted state into the in-memory cache. Call once at startup (suspend). */
    suspend fun load() {
        val saved = projectsFlow.first()
        projects = saved.toMutableList()
        val currentId = dataStore.data.first()[CURRENT_PROJECT_PREF_KEY]
        currentProjectId = currentId
        loaded = true
    }

    /** Synchronous read matching the (now cached) in-memory semantics. */
    fun getCurrentProject(): AudioProject? {
        if (currentProjectId == null || !loaded) return null
        return projects.firstOrNull { it.id == currentProjectId }
    }

    /** Synchronous list of all known projects. */
    fun getProjects(): List<AudioProject> = projects

    /** Set the current project (adding it to the library if new). Persists in background. */
    fun setCurrentProject(project: AudioProject?) {
        if (project == null) {
            currentProjectId = null
            persistAsync()
            return
        }
        val existing = projects.any { it.id == project.id }
        projects = if (existing) {
            projects.map { if (it.id == project.id) project else it }
        } else {
            projects + project
        }
        currentProjectId = project.id
        persistAsync()
    }

    fun createNewProject(name: String = "New Project"): AudioProject {
        val project = AudioProject(name = name)
        setCurrentProject(project)
        return project
    }

    /** Save/update a project in the library. */
    fun saveProject(project: AudioProject) {
        val existing = projects.any { it.id == project.id }
        projects = if (existing) {
            projects.map { if (it.id == project.id) project else it }
        } else {
            projects + project
        }
        persistAsync()
    }

    /** Delete a project from the library. */
    fun deleteProject(projectId: String) {
        projects = projects.filterNot { it.id == projectId }
        if (currentProjectId == projectId) currentProjectId = null
        persistAsync()
    }

    fun getProject(projectId: String): AudioProject? = projects.firstOrNull { it.id == projectId }

    /** Apply a transform to the current project in place: ui updates the cache and view immediately. */
    fun updateCurrentProject(transform: (AudioProject) -> AudioProject): AudioProject? {
        val cur = getCurrentProject() ?: return null
        val updated = transform(cur)
        setCurrentProject(updated)
        return updated
    }

    private fun persistAsync() {
        // Fire-and-forget write on the IO scope.
        ioScope.launch {
            runCatching {
                dataStore.edit { prefs ->
                    prefs[PROJECTS_PREF_KEY] = json.encodeToString(ListSerializer(AudioProject.serializer()), projects)
                    if (currentProjectId != null) {
                        prefs[CURRENT_PROJECT_PREF_KEY] = currentProjectId!!
                    } else {
                        prefs.remove(CURRENT_PROJECT_PREF_KEY)
                    }
                }
            }
        }
    }

    /** Current project snapshot for the editor scope (Main thread friendly). */
    val editor: CoroutineScope get() = editorScope
}
