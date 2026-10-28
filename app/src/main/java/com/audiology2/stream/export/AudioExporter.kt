package com.audiology2.stream.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.audiology2.stream.audio.WavWriter
import com.audiology2.stream.config.PcmFormat
import com.audiology2.stream.ui.AudioProject
import com.audiology2.stream.ui.Clip
import com.audiology2.stream.ui.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio export functionality - mixes tracks and exports to various formats.
 */
class AudioExporter(
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AudioExporter"
    }

    /** Export project to WAV file (uncompressed PCM) */
    suspend fun exportWav(
        project: AudioProject,
        outputFile: File,
        onProgress: ((Double) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sampleRate = project.sampleRate
            val totalFrames = (project.duration * sampleRate).toLong()
            val channels = project.tracks.maxByOrNull { it.clips.maxOfOrNull { it.duration }?.let { 2 } ?: 1 }?.clips?.maxOfOrNull { 2 } ?: 1
            
            // For simplicity, export as stereo
            val outputChannels = 2
            
            val fileOutputStream = FileOutputStream(outputFile)
            val wavWriter = WavWriter(
                file = outputFile,
                sampleRate = sampleRate,
                channels = outputChannels,
                pcmFormat = PcmFormat.PCM_16
            )
            
            // Create a simple mixer
            val mixer = TrackMixer(project, sampleRate)
            
            val bufferSize = 4096
            val buffer = FloatArray(bufferSize * 2) // stereo
            var framesWritten = 0L
            
            while (framesWritten < totalFrames) {
                val framesToWrite = min(bufferSize, (totalFrames - framesWritten).toInt())
                val framesMixed = mixer.mixFrames(buffer, framesToWrite)
                
                if (framesMixed > 0) {
                    wavWriter.writeSamples(buffer, framesMixed, 2)
                    framesWritten += framesMixed.toLong()
                } else {
                    break
                }
                
                onProgress?.invoke(framesWritten.toDouble() / totalFrames)
            }
            
            wavWriter.close()
            Log.i(TAG, "WAV export complete: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WAV export failed", e)
            false
        }
    }

    /** Export project to MP4/M4A (AAC encoded) */
    suspend fun exportM4a(
        project: AudioProject,
        outputFile: File,
        bitrate: Int = 256000,
        onProgress: ((Double) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val sampleRate = project.sampleRate
            val channels = 2
            
            val mimeType = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            
            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var encoderTrackIndex = -1
            
            val mixer = TrackMixer(project, sampleRate)
            val totalFrames = (project.duration * sampleRate).toLong()
            val bufferSize = 4096
            val inputBuffer = FloatArray(bufferSize * 2)
            var framesWritten = 0L
            var sawEOS = false
            
            val inputBufferInfo = MediaCodec.BufferInfo()
            
            while (!sawEOS) {
                val framesMixed = mixer.mixFrames(inputBuffer, bufferSize)
                
                if (framesMixed > 0) {
                    // Convert float to 16-bit PCM for encoder
                    val pcmBuffer = ByteBuffer.allocate(framesMixed * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until framesMixed * 2) {
                        val sample = (inputBuffer[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        pcmBuffer.putShort(sample.toShort())
                    }
                    pcmBuffer.flip()
                    
                    // Feed to encoder
                    val inputBufferIndex = encoder.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        val encoderInputBuffer = encoder.getInputBuffer(inputBufferIndex)
                        encoderInputBuffer?.put(pcmBuffer)
                        val presentationTimeUs = (framesWritten * 1000000L) / sampleRate
                        encoder.queueInputBuffer(inputBufferIndex, 0, pcmBuffer.remaining(), presentationTimeUs, 0)
                    }
                    framesWritten += framesMixed.toLong()
                    
                    onProgress?.invoke(framesWritten.toDouble() / totalFrames)
                } else {
                    // Signal EOS
                    val inputBufferIndex = encoder.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    }
                }
                
                // Drain encoder output
                val outputBufferIndex = encoder.dequeueOutputBuffer(inputBufferInfo, 5000)
                if (outputBufferIndex >= 0) {
                    if (encoderTrackIndex == -1) {
                        encoderTrackIndex = muxer.addTrack(encoder.getOutputFormat())
                        muxer.start()
                    }
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer == null) continue
                    if ((inputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    } else {
                        muxer.writeSampleData(encoderTrackIndex, outputBuffer, inputBufferInfo)
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    if ((inputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEOS = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    encoderTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                }
            }
            
            encoder.stop()
            encoder.release()
            muxer.stop()
            muxer.release()
            
            Log.i("AudioExporter", "M4A export complete: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("AudioExporter", "M4A export failed", e)
            false
        }
    }

    /** Export project to multiple formats */
    suspend fun exportAll(
        project: AudioProject,
        outputDir: File,
        formats: List<ExportFormat> = listOf(ExportFormat.WAV, ExportFormat.M4A),
        onProgress: ((ExportFormat, Double) -> Unit)? = null
    ): Map<ExportFormat, Boolean> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<ExportFormat, Boolean>()
        for (format in formats) {
            val outputFile = File(outputDir, "${project.name.replace(" ", "_")}.${format.extension}")
            val success = when (format) {
                ExportFormat.WAV -> exportWav(project, outputFile) { progress ->
                    onProgress?.invoke(format, progress)
                }
                ExportFormat.M4A -> exportM4a(project, outputFile) { progress ->
                    onProgress?.invoke(format, progress)
                }
                ExportFormat.MP3, ExportFormat.OGG, ExportFormat.FLAC -> false // Not implemented yet
            }
            results[format] = success
        }
        results
    }
}

/** Simple track mixer for mixing multiple tracks */
class TrackMixer(
    project: AudioProject,
    sampleRate: Int
) {
    private val tracks = project.tracks.map { track ->
        TrackPlayer(track, sampleRate)
    }.filter { it.hasAudio }.toList()
    
    private var currentFrame = 0L
    
    fun mixFrames(buffer: FloatArray, maxFrames: Int): Int {
        val framesToMix = min(buffer.size / 2, maxFrames)
        if (framesToMix <= 0) return 0
        
        // Clear buffer
        for (i in 0 until framesToMix * 2) {
            buffer[i] = 0f
        }
        
        var anyAudio = false
        for (track in tracks) {
            val framesRead = track.readFrames(buffer, framesToMix)
            if (framesRead > 0) {
                anyAudio = true
            }
        }
        
        if (!anyAudio) return 0
        
        // Apply master gain and clip prevention
        for (i in 0 until framesToMix * 2) {
            val sample = buffer[i]
            val clamped = sample.coerceIn(-1f, 1f)
            buffer[i] = clamped
        }
        
        return framesToMix
    }
    
    fun hasAudio(): Boolean = tracks.any { it.hasAudio }
}

/** Plays a single track's clips */
class TrackPlayer(
    track: Track,
    sampleRate: Int
) {
    private val clips = track.clips.sortedBy { it.startTime }
    private var currentClipIndex = 0
    private var clipFrameOffset = 0L
    
    val hasAudio: Boolean = clips.any { it.duration > 0 }
    
    fun readFrames(buffer: FloatArray, maxFrames: Int): Int {
        // Simplified - in real implementation, would decode audio files
        // For now, return 0 (silence) as placeholder
        return 0
    }
}

enum class ExportFormat(val extension: String) {
    WAV("wav"),
    M4A("m4a"),
    MP3("mp3"),
    OGG("ogg"),
    FLAC("flac")
}