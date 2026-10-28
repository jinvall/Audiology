package com.audiology2.stream.audio

import com.audiology2.stream.config.PcmFormat

/**
 * Immutable snapshot of capture health + live metering, delivered from the
 * capture thread to the UI periodically. Pure data class (no Android imports).
 */
data class CaptureStats(
    val rmsDb: Double = -96.0,
    val peakDb: Double = -96.0,
    val clipped: Boolean = false,
    val active: Boolean = false,
    val sampleRate: Int = 48000,
    val channels: Int = 1,
    val pcmFormat: PcmFormat = PcmFormat.PCM_16,
    val encodingName: String = "PCM_16",
    val bufferMs: Int = 20,
    /** Packet duration actually realized after MTU clamping (ms). */
    val effectivePacketMs: Int = 30,
    /** Wall-clock sample frames successfully read from the mic. */
    val framesRead: Long = 0,
    /** Times AudioRecord reported a read error or underrun. */
    val readErrors: Long = 0,
    /** Packets dropped because the network/send queue was full. */
    val dropped: Long = 0,
    /** Human-readable error message from the capture thread, if any. */
    val error: String = "",
    val running: Boolean = false
)
