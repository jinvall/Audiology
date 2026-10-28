package com.audiology2.stream.audio

import com.audiology2.stream.config.PcmFormat
import kotlin.math.roundToInt

/**
 * Encodes normalized float samples in [-1, 1] (interleaved) into the wire
 * PCM byte layout defined by [Protocol.PayloadType]. Pure Kotlin (no Android)
 * so it can be unit-tested on the host.
 *
 * Little-endian byte order on the wire.
 */
object PcmEncoder {

    /** Bytes per sample for [format]. */
    fun bytesPerSample(format: PcmFormat): Int = when (format) {
        PcmFormat.PCM_8 -> 1
        PcmFormat.PCM_16 -> 2
        PcmFormat.PCM_24 -> 3
        PcmFormat.PCM_32 -> 4
    }

    /** Write [frameCount] interleaved samples from [src] (offset 0) into [dst] at [dstOffsetBytes]. */
    fun encode(src: FloatArray, dst: ByteArray, dstOffsetBytes: Int, frameCount: Int, channels: Int, format: PcmFormat): Int {
        val bps = bytesPerSample(format)
        var p = dstOffsetBytes
        for (i in 0 until frameCount * channels) {
            var v = src[i]
            // hard clip to [-1,1] to avoid overflow distortion
            if (v > 1.0f) v = 1.0f else if (v < -1.0f) v = -1.0f
            val s = (v * 32767.0f).roundToInt().coerceIn(-32768, 32767)
            when (bps) {
                1 -> {
                    dst[p++] = ((s / 256) + 128).toByte()
                }
                2 -> {
                    dst[p++] = (s and 0xFF).toByte()
                    dst[p++] = ((s shr 8) and 0xFF).toByte()
                }
                3 -> {
                    // 24-bit: store 3 bytes LE of the 32-bit sample
                    val i32 = s shl 8
                    dst[p++] = (i32 and 0xFF).toByte()
                    dst[p++] = ((i32 shr 8) and 0xFF).toByte()
                    dst[p++] = ((i32 shr 16) and 0xFF).toByte()
                }
                4 -> {
                    val i32 = s shl 16
                    dst[p++] = (i32 and 0xFF).toByte()
                    dst[p++] = ((i32 shr 8) and 0xFF).toByte()
                    dst[p++] = ((i32 shr 16) and 0xFF).toByte()
                    dst[p++] = ((i32 shr 24) and 0xFF).toByte()
                }
            }
        }
        return p - dstOffsetBytes
    }

    /** Convenience: allocate and encode a full packet payload. */
    fun encodeFrame(src: FloatArray, frameCount: Int, channels: Int, format: PcmFormat): ByteArray {
        val n = frameCount * channels * bytesPerSample(format)
        val dst = ByteArray(n)
        encode(src, dst, 0, frameCount, channels, format)
        return dst
    }
}
