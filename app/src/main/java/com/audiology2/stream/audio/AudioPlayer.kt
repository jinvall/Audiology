package com.audiology2.stream.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.audiology2.stream.config.PcmFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Audio playback engine using AudioTrack.
 * Supports WAV file playback with seek, pause/resume.
 */
class AudioPlayer(
    private val scope: CoroutineScope
) {

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val currentPosition = AtomicLong(0L)
    private var currentFile: File? = null
    private var sampleRate = 48000
    private var channels = 1
    private var pcmFormat = PcmFormat.PCM_16
    private var totalFrames = 0L
    private var bytesPerFrame = 2
    private var wavDataStart = 0L
    private var wavDataSize = 0L
    
    // Callbacks
    var onPositionUpdate: ((Double) -> Unit)? = null
    var onPlaybackEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Load WAV file for playback. Returns true if successful. */
    fun loadFile(file: File): Boolean {
        stop()
        
        currentFile = file
        return try {
            val fis = FileInputStream(file)
            val header = ByteArray(44)
            fis.read(header)
            
            // Parse WAV header
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            
            // RIFF header (skip 12 bytes)
            bb.position(20)
            val wavAudioFormat = bb.getShort()
            channels = bb.getShort().toInt()
            sampleRate = bb.getInt()
            val byteRate = bb.getInt()
            val blockAlign = bb.getShort().toInt()
            val bitsPerSample = bb.getShort().toInt()
            
            // Find data chunk
            var chunkId = 0
            var chunkSize = 0
            var foundData = false
            while (fis.available() > 8) {
                val chunkHeader = ByteArray(8)
                fis.read(chunkHeader)
                val cb = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)
                chunkId = cb.getInt()
                chunkSize = cb.getInt()
                if (chunkId == 0x61746164) { // "data"
                    foundData = true
                    break
                } else {
                    fis.skip(chunkSize.toLong())
                }
            }
            
            if (!foundData) {
                fis.close()
                onError?.invoke("No data chunk found in WAV")
                return false
            }
            
            wavDataStart = fis.channel.position()
            wavDataSize = chunkSize.toLong()
            totalFrames = wavDataSize / (channels * bitsPerSample / 8)
            bytesPerFrame = channels * bitsPerSample / 8
            
            val audioEncoding = when (bitsPerSample) {
                8 -> AudioFormat.ENCODING_PCM_8BIT
                16 -> AudioFormat.ENCODING_PCM_16BIT
                else -> AudioFormat.ENCODING_PCM_FLOAT
            }
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
            val bufferSize = maxOf(minBufferSize, sampleRate * channels * 2) // ~1 second buffer
            
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(audioEncoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // SESSION_ID_GENERATE = 0 (API 26+)
                    0
                } else {
                    0
                }
            )
            
            fis.close()
            currentPosition.set(0)
            true
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Load failed", e)
            onError?.invoke("Failed to load: ${e.message}")
            false
        }
    }

    /** Start playback from current position */
    fun play() {
        if (audioTrack == null || currentFile == null) {
            onError?.invoke("No file loaded")
            return
        }
        
        if (isPlaying.get()) return
        isPlaying.set(true)
        isPaused.set(false)
        
        // Seek to current position
        seekToFrames(currentPosition.get())
        
        scope.launch(Dispatchers.IO) {
            playInternal()
        }
    }

    /** Pause playback */
    fun pause() {
        if (!isPlaying.get()) return
        isPaused.set(true)
        audioTrack?.pause()
    }

    /** Resume from pause */
    fun resume() {
        if (!isPaused.get()) return
        isPaused.set(false)
        audioTrack?.play()
    }

    /** Stop playback and reset */
    fun stop() {
        isPlaying.set(false)
        isPaused.set(false)
        audioTrack?.stop()
        audioTrack?.flush()
        currentPosition.set(0)
    }

    /** Seek to time in seconds */
    fun seek(timeSec: Double) {
        val frames = (timeSec * sampleRate).toLong().coerceIn(0L, totalFrames)
        seekToFrames(frames)
    }

    /** Seek to frame position */
    private fun seekToFrames(frames: Long) {
        if (currentFile == null) return
        
        val targetFrames = frames.coerceIn(0L, totalFrames)
        currentPosition.set(targetFrames)
        
        val byteOffset = wavDataStart + targetFrames * bytesPerFrame
        try {
            val raf = java.io.RandomAccessFile(currentFile!!, "r")
            raf.seek(byteOffset)
            raf.close()
        } catch (e: Exception) {
            Log.w("AudioPlayer", "Seek failed", e)
        }
    }

    private fun playInternal() {
        audioTrack?.play()
        
        val fis = FileInputStream(currentFile!!)
        val buffer = ByteArray(8192)
        
        try {
            // Seek to current position
            val byteOffset = wavDataStart + currentPosition.get() * bytesPerFrame
            fis.channel.position(byteOffset)
            
            var bytesRead: Int
            while (isPlaying.get() && !isPaused.get() && 
                   currentPosition.get() < totalFrames) {
                
                if (isPaused.get()) {
                    Thread.sleep(50)
                    continue
                }
                
                bytesRead = fis.read(buffer)
                if (bytesRead <= 0) break
                
                audioTrack?.write(buffer, 0, bytesRead)
                val framesAdvanced = bytesRead / bytesPerFrame
                currentPosition.addAndGet(framesAdvanced.toLong())
                
                // Notify position update every ~100ms
                if (currentPosition.get() % (sampleRate / 10) == 0L) {
                    val timeSec = currentPosition.get().toDouble() / sampleRate
                    scope.launch(Dispatchers.Main) {
                        onPositionUpdate?.invoke(timeSec)
                    }
                }
            }
            
            if (isPlaying.get() && currentPosition.get() >= totalFrames) {
                scope.launch(Dispatchers.Main) {
                    onPlaybackEnd?.invoke()
                }
            }
            
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Playback error", e)
            scope.launch(Dispatchers.Main) {
                onError?.invoke("Playback error: ${e.message}")
            }
        } finally {
            try { fis.close() } catch (_: Exception) {}
            isPlaying.set(false)
            if (!isPaused.get()) {
                audioTrack?.stop()
            }
        }
    }

    /** Get current playback time in seconds */
    fun getCurrentTimeSec(): Double = currentPosition.get().toDouble() / sampleRate

    /** Get total duration in seconds */
    fun getDurationSec(): Double = totalFrames.toDouble() / sampleRate

    /** Check if currently playing */
    fun getIsPlaying(): Boolean = isPlaying.get()

    /** Check if paused */
    fun getIsPaused(): Boolean = isPaused.get()

    /** Get sample rate */
    fun getSampleRate(): Int = sampleRate

    /** Get channels */
    fun getChannels(): Int = channels
}

private fun maxOf(a: Int, b: Int): Int = if (a > b) a else b