package com.audiology2.stream.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Versioned binary audio-streaming protocol.
 *
 * Wire format (all multi-byte fields little-endian):
 *
 *   offset  size  field
 *   0       4     magic        ASCII "ASIO"
 *   4       1     version      protocol version (currently 1)
 *   5       1     payloadType  0=PCM_8 1=PCM_16 2=PCM_24 3=PCM_32
 *   6       1     channels     1 (mono) or 2 (stereo)
 *   7       1     reserved     0
 *   8       4     sampleRate   uint32 LE (e.g. 48000)
 *   12      2     sessionId    uint16 LE, constant for a stream session
 *   14      4     sequence     uint32 LE, monotonically increasing, wraps at 2^32
 *   18      8     samplePos    uint64 LE, running sample-frame count at packet start
 *   26      2     payloadLen   uint16 LE, number of payload bytes that follow
 *   28      N     payload      exactly [payloadLen] bytes of PCM, little-endian
 *
 * Header size is constant: [HEADER_SIZE] = 28 bytes.
 *
 * UDP packets are kept under [MAX_PAYLOAD] bytes to avoid IP fragmentation on a
 * standard 1500-byte LAN. TCP uses a length-prefixed framing (2-byte uint16 LE
 * prefix giving total packet length) identical to the UDP payload.
 *
 * Design notes:
 *  - The receiver uses [samplePos] + [sampleRate] to resynchronize after packet
 *    loss and to discard stale audio.
 *  - A new [sessionId] is chosen per stream; a receiver treats a mismatch as a
 *    fresh session and flushes its jitter buffer.
 */
object Protocol {
    val MAGIC: ByteArray = "ASIO".toByteArray(Charsets.US_ASCII)
    const val MAGIC_STRING = "ASIO"
    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 28
    const val MAX_PAYLOAD: Int = 1400
    const val MAX_PACKET_SIZE: Int = HEADER_SIZE + MAX_PAYLOAD

    /**
     * Result of attempting to parse a packet from raw bytes.
     * @throws ProtocolException when the bytes cannot be interpreted as a valid packet.
     */
    @JvmInline
    value class ParseResult(val packet: Packet?) {
        val isFailure: Boolean get() = packet == null
    }

    enum class PayloadType(val value: Int, val bytesPerSample: Int, val bitsPerSample: Int, val signed: Boolean) {
        PCM_8(0, 1, 8, signed = true),
        PCM_16(1, 2, 16, signed = true),
        PCM_24(2, 3, 24, signed = true),
        PCM_32(3, 4, 32, signed = true);

        companion object {
            fun fromValue(v: Int): PayloadType? = entries.firstOrNull { it.value == v }
        }
    }

    class ProtocolException(message: String, val reason: Reason) : RuntimeException(message) {
        enum class Reason { BAD_MAGIC, BAD_VERSION, BAD_PAYLOAD_TYPE, BAD_CHANNELS, LENGTH_TOO_SHORT, LENGTH_TOO_LONG }
    }

    /**
     * Build a packet for sending. The payload is copied so caller buffers can be reused.
     * @throws IllegalArgumentException if channels < 1, or payloadLen exceeds MAX_PAYLOAD.
     */
    fun build(
        version: Int = VERSION,
        payloadType: PayloadType,
        channels: Int,
        sampleRate: Int,
        sessionId: Int,
        sequence: Long,
        samplePosition: Long,
        payload: ByteArray,
        payloadLen: Int = payload.size
    ): ByteArray {
        require(channels >= 1 && channels <= 2) { "channels must be 1 or 2" }
        require(version == VERSION) { "only protocol version $VERSION is supported" }
        require(payloadLen <= MAX_PAYLOAD) { "payloadLen $payloadLen exceeds MAX_PAYLOAD $MAX_PAYLOAD" }
        require(payloadLen <= payload.size) { "payloadLen $payloadLen exceeds buffer size ${payload.size}" }

        val out = ByteArray(HEADER_SIZE + payloadLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(MAGIC) // 0..3
        bb.put(version.toByte()) // 4
        bb.put(payloadType.value.toByte()) // 5
        bb.put(channels.toByte()) // 6
        bb.put(0) // 7 reserved
        bb.putInt(sampleRate) // 8..11
        bb.putShort(sessionId.toShort()) // 12..13
        bb.putInt(sequence.toInt()) // 14..17  (low 32 bits of uint32)
        bb.putLong(samplePosition) // 18..25
        bb.putShort(payloadLen.toShort()) // 26..27
        bb.put(payload, 0, payloadLen) // 28..
        return out
    }

    /** A parsed, immutable packet view (does not own/copy the payload bytes). */
    class Packet private constructor(
        val version: Int,
        val payloadType: PayloadType,
        val channels: Int,
        val sampleRate: Int,
        val sessionId: Int,
        val sequence: Long, // uint32 as Long
        val samplePosition: Long, // uint64
        val payload: ByteArray,
        val payloadOffset: Int,
        val payloadLen: Int
    ) {
        val frameCount: Int
            get() {
                val bytesPerFrame = channels * payloadType.bytesPerSample
                return if (bytesPerFrame == 0) 0 else payloadLen / bytesPerFrame
            }

        companion object {
            /**
             * Parse a packet from [bytes] starting at [offset] with [length].
             * Returns a Packet view over the supplied buffer (no copy of payload),
             * or throws [ProtocolException] if the bytes are malformed/incompatible.
             */
            fun parse(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Packet {
                if (length < HEADER_SIZE) {
                    throw ProtocolException("buffer too short: $length < $HEADER_SIZE", ProtocolException.Reason.LENGTH_TOO_SHORT)
                }
                val bb = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4)
                bb.get(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw ProtocolException("bad magic: ${magic.joinToString("") { "%02x".format(it) }}", ProtocolException.Reason.BAD_MAGIC)
                }
                val version = bb.get().toInt()
                if (version != VERSION) {
                    throw ProtocolException("unsupported version: $version (only $VERSION)", ProtocolException.Reason.BAD_VERSION)
                }
                val pt = PayloadType.fromValue(bb.get().toInt() and 0xFF)
                if (pt == null) {
                    throw ProtocolException("unknown payload type", ProtocolException.Reason.BAD_PAYLOAD_TYPE)
                }
                val channels = bb.get().toInt() and 0xFF
                if (channels !in 1..2) {
                    throw ProtocolException("bad channels: $channels", ProtocolException.Reason.BAD_CHANNELS)
                }
                bb.get() // reserved
                val sampleRate = bb.int
                val sessionId = bb.short.toInt() and 0xFFFF
                val sequence = bb.int.toLong() and 0xFFFFFFFFL
                val samplePosition = bb.long
                val payloadLen = bb.short.toInt() and 0xFFFF
                val payloadOffset = offset + HEADER_SIZE
                if (payloadLen > MAX_PAYLOAD) {
                    throw ProtocolException("payload too large: $payloadLen > $MAX_PAYLOAD", ProtocolException.Reason.LENGTH_TOO_LONG)
                }
                if (offset + HEADER_SIZE + payloadLen > offset + length) {
                    throw ProtocolException("declared payload exceeds available bytes", ProtocolException.Reason.LENGTH_TOO_LONG)
                }
                return Packet(
                    version = version,
                    payloadType = pt,
                    channels = channels,
                    sampleRate = sampleRate,
                    sessionId = sessionId,
                    sequence = sequence,
                    samplePosition = samplePosition,
                    payload = bytes,
                    payloadOffset = payloadOffset,
                    payloadLen = payloadLen
                )
            }
        }
    }

    /**
     * Recommended payload size (in bytes) for one packet given audio parameters
     * and a desired [packetMs] duration, clamped to [MAX_PAYLOAD].
     */
    fun recommendedPayloadLen(sampleRate: Int, channels: Int, bytesPerSample: Int, packetMs: Int): Int {
        if (sampleRate <= 0 || channels <= 0 || bytesPerSample <= 0 || packetMs <= 0) return 0
        val samples = (sampleRate * packetMs + 999) / 1000 // round up frames
        var n = samples * channels * bytesPerSample
        if (n > MAX_PAYLOAD) n = MAX_PAYLOAD
        return n
    }

    /**
     * Decode the packet's PCM payload into 16-bit signed samples (interleaved),
     * little-endian. Used by tests and by any pure-Kotlin consumer of recorded frames.
     * The Java/Android side is normally only a sender; the Python receiver decodes
     * independently, but this keeps the conversion logic testable.
     */
    fun decodeToInt16(packet: Packet): ShortArray {
        val frames = packet.frameCount
        val ch = packet.channels
        val out = ShortArray(frames * ch)
        val p = packet.payload
        val off = packet.payloadOffset
        val bps = packet.payloadType.bytesPerSample
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val base = off + (f * ch + c) * bps
                out[o++] = when (bps) {
                    1 -> {
                        // 8-bit unsigned PCM (0..255), center at 128
                        val v = (p[base].toInt() and 0xFF) - 128
                        (v * 256).toShort()
                    }
                    2 -> {
                        // 16-bit little-endian signed
                        val lo = p[base].toInt() and 0xFF
                        val hi = p[base + 1].toInt() and 0xFF
                        var v = lo or (hi shl 8)
                        if (v and 0x8000 != 0) v = v or 0xFFFF0000.toInt()
                        v.toShort()
                    }
                    3 -> {
                        // 24-bit little-endian signed, sign-extended
                        val b0 = p[base].toInt() and 0xFF
                        val b1 = p[base + 1].toInt() and 0xFF
                        val b2 = p[base + 2].toInt() and 0xFF
                        var v = b0 or (b1 shl 8) or (b2 shl 16)
                        if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
                        (v shr 8).toShort()
                    }
                    4 -> {
                        // 32-bit little-endian signed, scaled to 16 bits
                        val b0 = p[base].toInt() and 0xFF
                        val b1 = p[base + 1].toInt() and 0xFF
                        val b2 = p[base + 2].toInt() and 0xFF
                        val b3 = p[base + 3].toInt() and 0xFF
                        var v = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                        (v shr 16).toShort()
                    }
                    else -> 0
                }
            }
        }
        return out
    }
}
