package com.audiology2.stream.config

import com.audiology2.stream.dsp.AudioConfig
import kotlinx.serialization.Serializable

/** Network/transport selection. The receiver supports both. */
@Serializable
enum class TransportType { UDP, TCP }

/**
 * Android input source chosen for AudioRecord. Maps to MediaRecorder.AudioSource
 * constants in the capture layer. DEFAULT lets Android choose.
 */
@Serializable
enum class InputSource {
    DEFAULT,
    VOICE_COMMUNICATION,
    VOICE_CALL,
    CAMCORDER,
    UNPROCESSED,
    VOICE_RECOGNITION
}

/** Wire PCM format selected for streaming. */
@Serializable
enum class PcmFormat(val bytesPerSample: Int) { PCM_8(1), PCM_16(2), PCM_24(3), PCM_32(4) }

/** Channel layout requested from the hardware (mono/stereo). */
@Serializable
enum class ChannelMode { MONO, STEREO }

@Serializable
data class NetworkConfig(
    val host: String = "",
    val port: Int = 59090,
    val transport: TransportType = TransportType.TCP,
    val sampleRate: Int = 48000,
    val channelMode: ChannelMode = ChannelMode.MONO,
    val pcmFormat: PcmFormat = PcmFormat.PCM_16,
    /** Desired audio per-packet duration in milliseconds. */
    val packetMs: Int = 30,
    /** Receiver-side jitter buffer in ms (informational; clamped in receiver). */
    val jitterMs: Int = 60,
    val autoReconnect: Boolean = true
) {
    fun wireFormatBytesPerSample(): Int = when (pcmFormat) {
        PcmFormat.PCM_8 -> 1
        PcmFormat.PCM_16 -> 2
        PcmFormat.PCM_24 -> 3
        PcmFormat.PCM_32 -> 4
    }
}

@Serializable
data class CaptureConfig(
    val inputSource: InputSource = InputSource.DEFAULT,
    /** AudioRecord buffer size in ms. Small => lower latency, more CPU. */
    val bufferMs: Int = 20,
    /** Maximum-sensitivity mode: disables destructive gain staging defaults. */
    val maxSensitivity: Boolean = false,
    /** Raw/unprocessed bypass: skips all DSP and sends the raw capture. */
    val rawBypass: Boolean = false,
    /** Digital preamp applied before DSP, in dB. */
    val digitalGainDb: Double = 0.0
)

/**
 * Full application settings. [dsp] drives the processing chain.
 * Stored as a single JSON blob in DataStore for simplicity and atomic updates.
 */
@Serializable
data class AppSettings(
    val network: NetworkConfig = NetworkConfig(),
    val capture: CaptureConfig = CaptureConfig(),
    val dsp: AudioConfig = AudioConfig.DEFAULT,
    /** Name of the active preset, "" means "custom". */
    val activePreset: String = "",
    /** Opaque session id chosen at stream start. */
    val sessionId: Int = 0
) {
    val effectivePcmFormat: PcmFormat get() = network.pcmFormat
}

/** A named, versioned snapshot of settings the user can save/load. */
@Serializable
data class Preset(
    val name: String,
    val settings: AppSettings,
    val createdAt: Long = System.currentTimeMillis(),
    val builtIn: Boolean = false
)

/** Pure validation result, independent of Android. */
data class ValidationResult(val errors: List<String> = emptyList()) {
    val isOk: Boolean get() = errors.isEmpty()
    companion object {
        fun ok() = ValidationResult(emptyList())
        fun error(msg: String) = ValidationResult(listOf(msg))
    }
}

object Validators {
    fun validateHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.startsWith("[")) {
            val end = host.indexOf(']')
            if (end < 0) return false
            return host.substring(1, end).isNotBlank()
        }
        if (host.contains(':')) return false // bare IPv6 must use brackets
        val octets = host.split('.')
        if (octets.size == 4) {
            return octets.all { it.toIntOrNull()?.inRange(0, 255) == true }
        }
        // Hostname heuristic
        return host.length <= 253 &&
            host.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } &&
            !host.startsWith("-") && !host.endsWith("-")
    }

    private fun Int.inRange(lo: Int, hi: Int) = this >= lo && this <= hi

    fun validatePort(port: Int) = port in 1..65535

    fun validateSampleRate(rate: Int) = rate in SUPPORTED_SAMPLE_RATES

    fun validateAppSettings(s: AppSettings): ValidationResult {
        val errs = mutableListOf<String>()
        if (!validateHost(s.network.host)) errs.add("Invalid receiver host")
        if (!validatePort(s.network.port)) errs.add("Invalid port (1..65535)")
        if (!validateSampleRate(s.network.sampleRate)) errs.add("Unsupported sample rate")
        if (s.network.channelMode == ChannelMode.STEREO) {
            // stereo allowed; mono default. Nothing to reject.
        }
        if (s.capture.bufferMs !in 5..200) errs.add("Buffer size out of range (5..200 ms)")
        if (s.network.packetMs !in 5..200) errs.add("Packet duration out of range (5..200 ms)")
        if (s.network.jitterMs !in 0..500) errs.add("Jitter buffer out of range (0..500 ms)")
        if (s.capture.digitalGainDb !in -60.0..60.0) errs.add("Digital gain out of range (-60..60 dB)")
        return ValidationResult(errs)
    }

    val SUPPORTED_SAMPLE_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 64000, 96000)
}
