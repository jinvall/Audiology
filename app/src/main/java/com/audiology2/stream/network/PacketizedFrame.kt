package com.audiology2.stream.network

/**
 * A fully-framed protocol packet ready to hand to the network sender, plus the
 * metadata the sender needs for timing/stats without reparsing.
 * @property packet fully-built protocol datagram (header + payload).
 * @property samplePosition running sample-frame count of the first sample in the packet.
 * @property createdAtNs System.nanoTime() when framed (for jitter/loss timing).
 */
data class PacketizedFrame(
    val sessionId: Int,
    val sequence: Long,
    val samplePosition: Long,
    val createdAtNs: Long,
    val packet: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is PacketizedFrame &&
        sessionId == other.sessionId && sequence == other.sequence &&
        samplePosition == other.samplePosition && packet.contentEquals(other.packet)
    override fun hashCode(): Int {
        var h = sessionId
        h = 31 * h + sequence.hashCode()
        h = 31 * h + samplePosition.hashCode()
        return h
    }
}
