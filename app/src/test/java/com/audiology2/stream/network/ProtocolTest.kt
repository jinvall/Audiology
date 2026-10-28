package com.audiology2.stream.network

import com.audiology2.stream.config.PcmFormat
import com.audiology2.stream.dsp.dbDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContentEquals

class ProtocolTest {

    @Test
    fun buildAndParseRoundTrip() {
        val payload = ByteArray(640) { (it * 7).toByte() } // deterministic pseudo-random
        val packet = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16,
            channels = 1,
            sampleRate = 48000,
            sessionId = 0x1234,
            sequence = 42L,
            samplePosition = 9_000_000L,
            payload = payload,
            payloadLen = payload.size
        )
        assertTrue(packet.size >= Protocol.HEADER_SIZE + payload.size)
        val parsed = Protocol.Packet.parse(packet)
        assertEquals(Protocol.VERSION, parsed.version)
        assertEquals(Protocol.PayloadType.PCM_16, parsed.payloadType)
        assertEquals(1, parsed.channels)
        assertEquals(48000, parsed.sampleRate)
        assertEquals(0x1234, parsed.sessionId)
        assertEquals(42L, parsed.sequence)
        assertEquals(9_000_000L, parsed.samplePosition)
        assertEquals(payload.size, parsed.payloadLen)
        assertContentEquals(payload, parsed.payload.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLen))
        assertEquals(320, parsed.frameCount) // 640 bytes / 2 bytes / 1 channel
    }

    @Test
    fun rejectBadMagic() {
        val bytes = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = ByteArray(4), payloadLen = 4
        )
        bytes[0] = 'X'.code.toByte() // corrupt magic
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(bytes) }
        assertEquals(Protocol.ProtocolException.Reason.BAD_MAGIC, ex.reason)
    }

    @Test
    fun rejectBadVersion() {
        val bytes = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = ByteArray(4), payloadLen = 4
        )
        bytes[4] = 99 // version byte
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(bytes) }
        assertEquals(Protocol.ProtocolException.Reason.BAD_VERSION, ex.reason)
    }

    @Test
    fun rejectBadPayloadType() {
        val bytes = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = ByteArray(4), payloadLen = 4
        )
        bytes[5] = 7 // unknown payload type
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(bytes) }
        assertEquals(Protocol.ProtocolException.Reason.BAD_PAYLOAD_TYPE, ex.reason)
    }

    @Test
    fun rejectBadChannels() {
        val bytes = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = ByteArray(4), payloadLen = 4
        )
        bytes[6] = 0 // zero channels
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(bytes) }
        assertEquals(Protocol.ProtocolException.Reason.BAD_CHANNELS, ex.reason)
    }

    @Test
    fun rejectTooShort() {
        val shortBuf = ByteArray(10)
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(shortBuf) }
        assertEquals(Protocol.ProtocolException.Reason.LENGTH_TOO_SHORT, ex.reason)
    }

    @Test
    fun rejectPayloadTooLong() {
        val bytes = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = ByteArray(4), payloadLen = 4
        )
        // Claim a payload length exceeding MAX_PAYLOAD at the declared offset (26).
        bytes[26] = 0xFF.toByte()
        bytes[27] = 0x00
        val ex = assertFailsWith<Protocol.ProtocolException> { Protocol.Packet.parse(bytes) }
        assertEquals(Protocol.ProtocolException.Reason.LENGTH_TOO_LONG, ex.reason)
    }

    @Test
    fun sequenceNumbersMonotonic() {
        val seqs = (0..4).map { i ->
            Protocol.build(
                payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
                sessionId = 1, sequence = i.toLong(), samplePosition = (i * 100L),
                payload = ByteArray(2), payloadLen = 2
            )
        }
        val parsed = seqs.map { Protocol.Packet.parse(it) }
        val seqValues = parsed.map { it.sequence }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), seqValues)
    }

    @Test
    fun recommendedPayloadLenClampedToMTU() {
        // 48kHz, stereo, 16-bit => 4 bytes/sample-frame.
        // 30ms => 1440 samples > 1400/4 = 350 allowed => clamp to 350 (1400 bytes).
        val len = Protocol.recommendedPayloadLen(48000, 2, 2, 30)
        assertEquals(1400, len)
    }

    @Test
    fun encodeDecodePCM16Symmetry() {
        val samples = FloatArray(256) { i ->
            (kotlin.math.sin(2 * Math.PI * 440 * i / 48000.0) * 0.5).toFloat()
        }
        val encoded = com.audiology2.stream.audio.PcmEncoder.encodeFrame(samples, 256, 1, PcmFormat.PCM_16)
        assertEquals(512, encoded.size)
        // Build a packet and decode it
        val packet = Protocol.build(
            payloadType = Protocol.PayloadType.PCM_16, channels = 1, sampleRate = 48000,
            sessionId = 1, sequence = 0L, samplePosition = 0L, payload = encoded, payloadLen = encoded.size
        )
        val decoded = Protocol.decodeToInt16(Protocol.Packet.parse(packet))
        assertEquals(256, decoded.size)
        for (i in decoded.indices) {
            val expected = (samples[i] * 32767.0).toInt().coerceIn(-32768, 32767)
            assertTrue(kotlin.math.abs(decoded[i].toInt() - expected) <= 1, "sample $i: ${decoded[i]} vs $expected")
        }
    }

    @Test
    fun dbDisplayWorks() {
        assertEquals("—", (-96.0).dbDisplay())
        assertTrue((-12.0).dbDisplay().contains("dBFS"))
    }
}
