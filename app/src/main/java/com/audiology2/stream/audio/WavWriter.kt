package com.audiology2.stream.audio

import com.audiology2.stream.config.PcmFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WAV file writer for recording audio to disk.
 * Supports PCM 16-bit, 24-bit, 32-bit float.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val pcmFormat: PcmFormat
) {

    private var fos: FileOutputStream? = null
    private var dataChunkSize = 0L
    private var isOpen = false

    private val bytesPerSample = pcmFormat.bytesPerSample

    init {
        open()
    }

    private fun open() {
        file.parentFile?.mkdirs()
        fos = FileOutputStream(file)
        writeHeader()
        isOpen = true
    }

    private fun writeHeader() {
        fos?.write("RIFF".toByteArray())
        // File size will be written when file is closed
        fos?.write(intToBytes(0))
        fos?.write("WAVE".toByteArray())
        
        // fmt chunk
        fos?.write("fmt ".toByteArray())
        fos?.write(intToBytes(16)) // Subchunk1Size = 16 for PCM
        
        val audioFormat = when (pcmFormat) {
            PcmFormat.PCM_8 -> 1
            PcmFormat.PCM_16 -> 1
            PcmFormat.PCM_24 -> 1
            PcmFormat.PCM_32 -> 3 // IEEE float
        }
        
        fos?.write(shortToBytes(audioFormat.toShort())) // AudioFormat (1 = PCM, 3 = IEEE float)
        fos?.write(shortToBytes(channels.toShort()))
        fos?.write(intToBytes(sampleRate))
        
        val byteRate = sampleRate * channels * bytesPerSample
        fos?.write(intToBytes(byteRate))
        fos?.write(shortToBytes((channels * bytesPerSample).toShort())) // BlockAlign
        fos?.write(shortToBytes((bytesPerSample * 8).toShort())) // BitsPerSample
        
        // data chunk header
        fos?.write("data".toByteArray())
        // data chunk size will be written when file is closed
        fos?.write(intToBytes(0))
    }

    /** Write audio samples from float array (-1.0 to 1.0) */
    fun writeSamples(samples: FloatArray, numFrames: Int, channels: Int): Boolean {
        if (!isOpen || fos == null) return false
        
        val totalSamples = numFrames * channels
        val byteBuffer = ByteBuffer.allocate(totalSamples * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until totalSamples) {
            val v = (samples[i] * 0.999f).coerceIn(-1.0f, 1.0f)
            when (pcmFormat) {
                PcmFormat.PCM_8 -> {
                    val scaled = ((v + 1.0f) * 127.5f).toInt().coerceIn(0, 255)
                    byteBuffer.put(scaled.toByte())
                }
                PcmFormat.PCM_16 -> {
                    val scaled = (v * 32767.0f).toInt().coerceIn(-32768, 32767)
                    byteBuffer.putShort(scaled.toShort())
                }
                PcmFormat.PCM_24 -> {
                    val scaled = (v * 8388607.0f).toLong().coerceIn(-8388608L, 8388607L)
                    val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(scaled.toInt()).array()
                    byteBuffer.put(bytes[0])
                    byteBuffer.put(bytes[1])
                    byteBuffer.put(bytes[2])
                }
                PcmFormat.PCM_32 -> {
                    byteBuffer.putFloat(v)
                }
            }
        }
        
        fos?.write(byteBuffer.array())
        dataChunkSize += byteBuffer.position().toLong()
        return true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        
        try {
            fos?.flush()
            
            // Update RIFF size (file size - 8)
            val fileSize = file.length()
            val raf = java.io.RandomAccessFile(file, "rw")
            raf.seek(4)
            raf.write(intToBytes((fileSize - 8).toInt()))
            
            // Update data chunk size
            raf.seek(40)
            raf.write(intToBytes(dataChunkSize.toInt()))
            raf.close()
            
            fos?.close()
            fos = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToBytes(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}