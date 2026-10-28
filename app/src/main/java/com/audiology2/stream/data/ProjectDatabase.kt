package com.audiology2.stream.data

import androidx.room.*
import com.audiology2.stream.ui.AudioProject
import com.audiology2.stream.ui.Clip
import com.audiology2.stream.ui.Track
import kotlinx.serialization.json.Json
import com.audiology2.stream.data.toJsonString
import com.audiology2.stream.data.toAudioProject

/** Room database for project persistence */
@Database(
    entities = [ProjectEntity::class, TrackEntity::class, ClipEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ProjectDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun trackDao(): TrackDao
    abstract fun clipDao(): ClipDao

    companion object {
        @Volatile
        private var INSTANCE: ProjectDatabase? = null

        fun getInstance(context: android.content.Context): ProjectDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ProjectDatabase::class.java,
                    "audiology2_projects.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

/** Project entity */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val sampleRate: Int,
    val bitDepth: Int,
    val masterGain: Float,
    val tempo: Double,
    val timeSignatureNumerator: Int,
    val timeSignatureDenominator: Int,
    val projectFolder: String
) {
    companion object {
        fun fromProject(project: AudioProject): ProjectEntity {
            return ProjectEntity(
                id = project.id,
                name = project.name,
                createdAt = project.createdAt,
                modifiedAt = project.modifiedAt,
                sampleRate = project.sampleRate,
                bitDepth = project.bitDepth,
                masterGain = project.masterGain,
                tempo = project.tempo,
                timeSignatureNumerator = project.timeSignatureNumerator,
                timeSignatureDenominator = project.timeSignatureDenominator,
                projectFolder = project.projectFolder
            )
        }
    }
}

/** Helper function to serialize project - kept separate to avoid kapt issues */

/** Track entity */
@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val gain: Float,
    val pan: Float,
    val muted: Boolean,
    val solo: Boolean,
    val color: Int,
    val height: Float,
    val armed: Boolean,
    val inputSource: String,
    val monitor: Boolean,
    val trackOrder: Int // For ordering
) {
    constructor(track: Track, projectId: String, order: Int) : this(
        id = track.id,
        projectId = projectId,
        name = track.name,
        gain = track.gain,
        pan = track.pan,
        muted = track.muted,
        solo = track.solo,
        color = track.color,
        height = track.height,
        armed = track.armed,
        inputSource = track.inputSource,
        monitor = track.monitor,
        trackOrder = order
    )

    fun toTrack(): Track = Track(
        id = id,
        name = name,
        clips = mutableListOf(), // Clips loaded separately
        gain = gain,
        pan = pan,
        muted = muted,
        solo = solo,
        color = color,
        height = height,
        armed = armed,
        inputSource = inputSource,
        monitor = monitor
    )
}

/** Clip entity */
@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("projectId")]
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val projectId: String,
    val name: String,
    val sourcePath: String,
    val sourceOffset: Double,
    val duration: Double,
    val startTime: Double,
    val gain: Float,
    val pan: Float,
    val muted: Boolean,
    val solo: Boolean,
    val color: Int,
    val fadeIn: Double,
    val fadeOut: Double,
    val locked: Boolean,
    val clipOrder: Int // For ordering within track
) {
    constructor(clip: Clip, trackId: String, projectId: String, order: Int) : this(
        id = clip.id,
        trackId = trackId,
        projectId = projectId,
        name = clip.name,
        sourcePath = clip.sourcePath,
        sourceOffset = clip.sourceOffset,
        duration = clip.duration,
        startTime = clip.startTime,
        gain = clip.gain,
        pan = clip.pan,
        muted = clip.muted,
        solo = clip.solo,
        color = clip.color,
        fadeIn = clip.fadeIn,
        fadeOut = clip.fadeOut,
        locked = clip.locked,
        clipOrder = order
    )

    fun toClip(): Clip = Clip(
        id = id,
        name = name,
        sourcePath = sourcePath,
        sourceOffset = sourceOffset,
        duration = duration,
        startTime = startTime,
        trackId = trackId,
        gain = gain,
        pan = pan,
        muted = muted,
        solo = solo,
        color = color,
        fadeIn = fadeIn,
        fadeOut = fadeOut,
        locked = locked
    )
}

/** DAO for projects */
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity)

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** DAO for tracks */
@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Delete
    suspend fun delete(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE projectId = :projectId ORDER BY trackOrder ASC")
    suspend fun getByProject(projectId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Query("DELETE FROM tracks WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}

/** DAO for clips */
@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(clips: List<ClipEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: ClipEntity)

    @Update
    suspend fun update(clip: ClipEntity)

    @Delete
    suspend fun delete(clip: ClipEntity)

    @Query("SELECT * FROM clips WHERE trackId = :trackId ORDER BY clipOrder ASC")
    suspend fun getByTrack(trackId: String): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE projectId = :projectId ORDER BY startTime ASC")
    suspend fun getByProject(projectId: String): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun getById(id: String): ClipEntity?

    @Query("DELETE FROM clips WHERE trackId = :trackId")
    suspend fun deleteByTrack(trackId: String)

    @Query("DELETE FROM clips WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}

/** Repository for project operations */
class ProjectRepository(private val db: ProjectDatabase) {
    private val projectDao = db.projectDao()
    private val trackDao = db.trackDao()
    private val clipDao = db.clipDao()

    /** Save complete project with tracks and clips */
    suspend fun saveProject(project: AudioProject) {
        val entity = ProjectEntity.fromProject(project)
        projectDao.insert(entity)
        
        // Save tracks
        project.tracks.forEachIndexed { index, track ->
            val trackEntity = TrackEntity(track, project.id, index)
            trackDao.insert(trackEntity)
            
            // Save clips
            track.clips.forEachIndexed { clipIndex, clip ->
                val clipEntity = ClipEntity(clip, track.id, project.id, clipIndex)
                clipDao.insert(clipEntity)
            }
        }
    }

    /** Load project with all tracks and clips */
    suspend fun loadProject(id: String): AudioProject? {
        val projectEntity = projectDao.getById(id) ?: return null
        
        // Load tracks
        val trackEntities = trackDao.getByProject(id)
        val tracks = mutableListOf<Track>()
        trackEntities.forEach { trackEntity ->
            val track = trackEntity.toTrack()
            // Load clips for this track
            val clipEntities = clipDao.getByTrack(trackEntity.id)
            clipEntities.forEach { clipEntity ->
                track.clips.add(clipEntity.toClip())
            }
            tracks.add(track)
        }
        
        // Reconstruct AudioProject
        return AudioProject(
            id = projectEntity.id,
            name = projectEntity.name,
            createdAt = projectEntity.createdAt,
            modifiedAt = projectEntity.modifiedAt,
            sampleRate = projectEntity.sampleRate,
            bitDepth = projectEntity.bitDepth,
            tracks = tracks,
            masterGain = 1.0f,
            tempo = projectEntity.tempo,
            timeSignatureNumerator = projectEntity.timeSignatureNumerator,
            timeSignatureDenominator = projectEntity.timeSignatureDenominator,
            projectFolder = projectEntity.projectFolder
        )
    }

    /** Get all projects */
    suspend fun getAllProjects(): List<ProjectEntity> {
        return projectDao.getAll()
    }

    /** Delete project and all associated data */
    suspend fun deleteProject(id: String) {
        projectDao.deleteById(id)
        // Cascading deletes handle tracks and clips
    }

    /** Update project metadata */
    suspend fun updateProjectMeta(project: AudioProject) {
        val entity = ProjectEntity.fromProject(project)
        projectDao.update(entity)
    }
}

/** Converter for Room */
class Converters {
    @TypeConverter
    fun fromDoubleList(list: List<Double>?): String? {
        if (list == null) return null
        return list.joinToString(",") { it.toString() }
    }

    @TypeConverter
    fun toDoubleList(json: String?): List<Double>? {
        if (json == null || json.isEmpty()) return null
        return json.split(",").map { it.toDouble() }
    }
    
    @TypeConverter
    fun fromFloatList(list: List<Float>?): String? {
        if (list == null) return null
        return list.joinToString(",") { it.toString() }
    }

    @TypeConverter
    fun toFloatList(json: String?): List<Float>? {
        if (json == null || json.isEmpty()) return null
        return json.split(",").map { it.toFloat() }
    }
    
    @TypeConverter
    fun fromIntList(list: List<Int>?): String? {
        if (list == null) return null
        return list.joinToString(",") { it.toString() }
    }

    @TypeConverter
    fun toIntList(json: String?): List<Int>? {
        if (json == null || json.isEmpty()) return null
        return json.split(",").map { it.toInt() }
    }
}