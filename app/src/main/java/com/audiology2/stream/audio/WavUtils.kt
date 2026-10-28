package com.audiology2.stream.audio

import com.audiology2.stream.config.PcmFormat
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Loaded PCM audio: interleaved float samples in [-1, 1]. */
class AudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val frames: Int
) {
    /** Extract a mono mixdown of the given frame range. */
    fun mixdown(startFrame: Int = 0, frameCount: Int = frames): FloatArray {
        val n = min(frameCount, frames - startFrame).coerceAtLeast(0)
        val out = FloatArray(n)
        var src = startFrame * channels
        for (i in 0 until n) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += samples[src + c]
            }
            out[i] = acc / channels
            src += channels
        }
        return out
    }
}

/**
 * WAV reading/writing utilities used by offline clip editing (pitch/speed,
 * effects, sample creation). Supports 8/16/24-bit PCM and 32-bit float.
 */
object WavUtils {

    /**
     * Read a WAV file into an [AudioBuffer]. Returns null if the file is not
     * a supported PCM WAV. Handles data chunks at arbitrary offsets.
     */
    fun read(file: File): AudioBuffer? {
        val fis = try {
            FileInputStream(file)
        } catch (e: Exception) {
            return null
        }
        return try {
            readStream(fis)
        } catch (e: Exception) {
            null
        } finally {
            try { fis.close() } catch (_: Exception) {}
        }
    }

    private fun readStream(fis: FileInputStream): AudioBuffer? {
        val header = ByteArray(12)
        val read = fis.read(header)
        if (read < 12) return null
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") return null

        var fmtChannels = 1
        var fmtSampleRate = 48000
        var bitsPerSample = 16
        var audioFormat = 1
        var foundFmt = false

        // Walk chunks
        while (true) {
            val chunkHdr = ByteArray(8)
            val n = fis.read(chunkHdr)
            if (n < 8) break
            val cb = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN)
            val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
            val size = cb.getInt(4)
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.coerceAtMost(64))
                    fis.read(fmt)
                    val fb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = fb.getShort(0).toInt()
                    fmtChannels = fb.getShort(2).toInt().coerceAtLeast(1)
                    fmtSampleRate = fb.getInt(4)
                    bitsPerSample = fb.getShort(14).toInt()
                    foundFmt = true
                    if (size > 16) {
                        // skip any extra fmt bytes
                        val extra = size - 16
                        if (extra > 0 && fis.available() > 0) fis.skip(extra.toLong())
                    }
                }
                "data" -> {
                    if (!foundFmt) return null
                    val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
                    val bytesPerFrame = bytesPerSample * fmtChannels
                    val frames = (size / bytesPerFrame).coerceAtLeast(0)
                    val samples = FloatArray(frames * fmtChannels)
                    val byteBuf = ByteBuffer.allocate(min(size, 1 shl 20)).order(ByteOrder.LITTLE_ENDIAN)
                    var pos = 0
                    var framesLeft = frames
                    while (framesLeft > 0) {
                        val framesToRead = min(framesLeft, byteBuf.capacity() / bytesPerFrame)
                        byteBuf.clear()
                        val want = framesToRead * bytesPerFrame
                        var got = 0
                        while (got < want) {
                            val nRead = fis.read(byteBuf.array(), got, want - got)
                            if (nRead <= 0) break
                            got += nRead
                        }
                        byteBuf.limit(got)
                        val array = byteBuf.array()
                        var i = 0
                        while (i + bytesPerFrame <= got) {
                            val base = pos * fmtChannels
                            for (c in 0 until fmtChannels) {
                                val s = i + c * bytesPerSample
                                samples[base + c] = when (bitsPerSample) {
                                    8 -> ((array[s].toInt() and 0xFF) - 128) / 128f
                                    16 -> ByteBuffer.wrap(array, s, 2).order(ByteOrder.LITTLE_ENDIAN)
                                        .short.toFloat() / 32768f
                                    24 -> {
                                        val v = (array[s].toInt() and 0xFF) or
                                            ((array[s + 1].toInt() and 0xFF) shl 8) or
                                            ((array[s + 2].toInt() and 0xFF) shl 16)
                                        val signed = if (v and 0x800000 != 0) v - 0x1000000 else v
                                        signed.toFloat() / 8388607f
                                    }
                                    else -> ByteBuffer.wrap(array, s, 4).order(ByteOrder.LITTLE_ENDIAN).float
                                }
                            }
                            i += bytesPerFrame
                            pos++
                        }
                        framesLeft -= framesToRead
                    }
                    return AudioBuffer(samples, fmtSampleRate, fmtChannels, frames)
                }
                else -> {
                    if (size > 0) fis.skip(size.toLong())
                }
            }
        }
        return null
    }

    /**
     * Read a contiguous range of a WAV file (used for clip source windows).
     * startSec/endSec are clamped to the file bounds.
     */
    fun readRange(file: File, startSec: Double, endSec: Double): AudioBuffer? {
        val full = read(file) ?: return null
        val startFrame = (startSec * full.sampleRate).toInt().coerceIn(0, full.frames)
        val endFrame = (endSec * full.sampleRate).toInt().coerceIn(startFrame, full.frames)
        val count = endFrame - startFrame
        if (count <= 0) return AudioBuffer(FloatArray(0), full.sampleRate, full.channels, 0)
        val offset = startFrame * full.channels
        val out = FloatArray(count * full.channels)
        System.arraycopy(full.samples, offset, out, 0, out.size)
        return AudioBuffer(out, full.sampleRate, full.channels, count)
    }

    /** Write float samples to a 16-bit PCM WAV. Creates parent directories. */
    fun write(file: File, buffer: AudioBuffer): Boolean = try {
        file.parentFile?.mkdirs()
        val writer = WavWriter(file, buffer.sampleRate, buffer.channels, PcmFormat.PCM_16)
        writer.writeSamples(buffer.samples, buffer.frames, buffer.channels)
        writer.close()
        true
    } catch (e: Exception) {
        false
    }

    /** Find the first non-silent frame index (leading silence trim helper). */
    fun findFirstNonSilent(mix: FloatArray, threshold: Float, window: Int): Int {
        var i = 0
        while (i < mix.size) {
            var peak = 0f
            val end = min(i + window, mix.size)
            for (j in i until end) {
                val a = kotlin.math.abs(mix[j])
                if (a > peak) peak = a
            }
            if (peak >= threshold) return i
            i = end
        }
        return 0
    }

    /** Find the last non-silent frame index. */
    fun findLastNonSilent(mix: FloatArray, threshold: Float, window: Int): Int {
        var i = mix.size
        while (i > 0) {
            var peak = 0f
            val start = (i - window).coerceAtLeast(0)
            for (j in start until i) {
                val a = kotlin.math.abs(mix[j])
                if (a > peak) peak = a
            }
            if (peak >= threshold) return i
            i = start
        }
        return mix.size
    }

    /** Normalize peak to [peakTarget] (linear, default 0.89 ≈ -1 dBFS). */
    fun normalize(samples: FloatArray, peakTarget: Float = 0.89f): FloatArray {
        var peak = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        if (peak <= 0f) return samples
        val gain = peakTarget / peak
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = (samples[i] * gain).coerceIn(-1f, 1f)
        return out
    }
}
