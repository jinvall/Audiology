package com.audiology2.stream.importing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.audiology2.stream.audio.WavWriter
import com.audiology2.stream.config.PcmFormat
import com.audiology2.stream.ui.AudioProject
import com.audiology2.stream.ui.Clip
import com.audiology2.stream.ui.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio import functionality - loads audio files and creates clips.
 */
class AudioImporter(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AudioImporter"
    }

    /** Import audio file from URI and create a new clip on a track.
     *  Returns the updated project plus the created track and clip. */
    suspend fun importAudioFile(
        project: AudioProject,
        uri: Uri,
        targetTrackId: String? = null,
        onProgress: ((Double) -> Unit)? = null
    ): Triple<AudioProject, Track, Clip>? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext null
            
            // Use MediaExtractor to decode
            val extractor = MediaExtractor()
            extractor.setDataSource(context.contentResolver.openFileDescriptor(uri, "r")!!.fileDescriptor)
            
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }
            
            if (audioTrackIndex == -1) {
                Log.e(TAG, "No audio track found")
                return@withContext null
            }
            
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            // Create decoder
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            // Decode to temporary WAV file
            val tempFile = File(context.cacheDir, "import_${UUID.randomUUID()}.wav")
            val wavWriter = WavWriter(tempFile, sampleRate, channels, PcmFormat.PCM_16)
            
            val inputBufferInfo = MediaCodec.BufferInfo()
            var sawEOS = false
            var framesDecoded = 0L
            
            while (!sawEOS) {
                val inputBufferIndex = decoder.dequeueInputBuffer(5000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    }
                }
                
                val outputBufferIndex = decoder.dequeueOutputBuffer(inputBufferInfo, 5000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (inputBufferInfo.size > 0) {
                        val floatBuffer = byteBufferToFloatArray(outputBuffer!!, inputBufferInfo.offset, inputBufferInfo.size, format)
                        wavWriter.writeSamples(floatBuffer, inputBufferInfo.size / (2 * channels), channels)
                        framesDecoded += inputBufferInfo.size.toLong() / (2 * channels)
                    }
                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                    if ((inputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEOS = true
                    }
                }
                
                onProgress?.invoke(1.0) // Decode loop complete
            }
            
            wavWriter.close()
            decoder.stop()
            decoder.release()
            extractor.release()
            
            // Create track and clip (immutable project flow)
            var workingTrack = targetTrackId?.let { project.getTrack(it) }
            var workingProject = project
            if (workingTrack == null) {
                val firstTrack = project.tracks.firstOrNull()
                if (firstTrack != null) {
                    workingTrack = firstTrack
                } else {
                    val added = workingProject.addTrack(name = "Imported", color = 0xFF4ECDC4.toInt())
                    workingProject = added
                    workingTrack = added.tracks.last()
                }
            }

            val clip = Clip(
                sourcePath = tempFile.absolutePath,
                duration = framesDecoded.toDouble() / sampleRate,
                startTime = workingTrack!!.duration,
                trackId = workingTrack.id,
                color = 0xFF4ECDC4.toInt()
            )
            val updatedTrack = workingTrack.appendClip(clip)
            workingProject = workingProject.updateTrack(updatedTrack)

            Log.i(TAG, "Imported audio: ${clip.duration}s, ${sampleRate}Hz, ${channels}ch")
            Triple(workingProject, updatedTrack, clip)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            null
        }
    }

    private fun byteBufferToFloatArray(buffer: java.nio.ByteBuffer, offset: Int, size: Int, format: MediaFormat): FloatArray {
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val bitDepth = 16 // assume 16-bit PCM from decoder
        val frameCount = size / (channels * bitDepth / 8)
        val result = FloatArray(frameCount * channels)
        
        val byteBuffer = buffer.duplicate()
        byteBuffer.position(offset)
        
        for (i in 0 until frameCount) {
            for (ch in 0 until channels) {
                val sample = buffer.getShort().toInt()
                result[i * channels + ch] = sample.toFloat() / 32768f
            }
        }
        return result
    }

    /** Import multiple files; returns the final updated project and all created clips. */
    suspend fun importMultipleFiles(
        project: AudioProject,
        uris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<AudioProject, List<Clip>> = withContext(Dispatchers.IO) {
        var workingProject = project
        val results = mutableListOf<Clip>()
        for ((index, uri) in uris.withIndex()) {
            val result = importAudioFile(workingProject, uri, null) { progress ->
                onProgress?.invoke(index, uris.size)
            }
            result?.let { (updatedProject, _, clip) ->
                workingProject = updatedProject
                results.add(clip)
            }
        }
        workingProject to results
    }
}