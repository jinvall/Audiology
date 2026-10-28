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

    /** Import audio file from URI and create a new track with clip */
    suspend fun importAudioFile(
        project: AudioProject,
        uri: Uri,
        targetTrackId: String? = null,
        onProgress: ((Double) -> Unit)? = null
    ): Pair<Track, Clip>? = withContext(Dispatchers.IO) {
        var result: Pair<Track, Clip>? = null
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
                
                onProgress?.invoke(0.5) // Placeholder
            }
            
            wavWriter.close()
            decoder.stop()
            decoder.release()
            extractor.release()
            
            // Create track and clip
            val track = targetTrackId?.let { project.getTrack(it) } 
                ?: project.tracks.firstOrNull() 
                ?: Track(name = "Imported").also { project.tracks.add(it) }
            
            val clip = Clip(
                sourcePath = tempFile.absolutePath,
                duration = framesDecoded.toDouble() / sampleRate,
                startTime = track.duration,
                trackId = track.id,
                color = 0xFF4ECDC4.toInt()
            )
            track.appendClip(clip)
            
            Log.i(TAG, "Imported audio: ${clip.duration}s, ${sampleRate}Hz, ${channels}ch")
            Pair(track, clip)
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

    /** Import multiple files */
    suspend fun importMultipleFiles(
        project: AudioProject,
        uris: List<Uri>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<Pair<Track, Clip>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Pair<Track, Clip>>()
        for ((index, uri) in uris.withIndex()) {
            onProgress?.invoke(index, uris.size)
            val result = importAudioFile(project, uri, null) { progress ->
                // Could update per-file progress
            }
            result?.let { results.add(it) }
        }
        results
    }
}