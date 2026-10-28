package com.audiology2.stream.config

import com.audiology2.stream.dsp.AudioConfig

/**
 * Built-in preset catalog. Each entry maps to an [AppSettings] snapshot.
 * Users can also save, rename, and reset custom presets.
 *
 * Presets intentionally do NOT enable destructive processing by default in
 * Maximum Sensitivity mode — quiet transients are preserved.
 */
object Presets {
    val BUILT_IN: List<Preset> = listOf(
        Preset(
            name = "Maximum Sensitivity",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.UDP, sampleRate = 48000,
                    channelMode = ChannelMode.MONO, pcmFormat = PcmFormat.PCM_16, packetMs = 30,
                    jitterMs = 60, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.UNPROCESSED,
                    bufferMs = 10, maxSensitivity = true, rawBypass = false,
                    digitalGainDb = 24.0),
                dsp = AudioConfig.DEFAULT.copy(
                    inputGainDb = 24.0,
                    // Deliberately leave AGC / noise suppression / compressor OFF
                    // so quiet sounds are not erased.
                ),
                activePreset = "Maximum Sensitivity"
            ),
            builtIn = true
        ),
        Preset(
            name = "Low Latency",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.UDP, sampleRate = 48000,
                    channelMode = ChannelMode.MONO, pcmFormat = PcmFormat.PCM_16, packetMs = 10,
                    jitterMs = 30, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.DEFAULT,
                    bufferMs = 5, maxSensitivity = false, rawBypass = true, digitalGainDb = 0.0),
                dsp = AudioConfig.DEFAULT,
                activePreset = "Low Latency"
            ),
            builtIn = true
        ),
        Preset(
            name = "Clean Monitoring",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.UDP, sampleRate = 44100,
                    channelMode = ChannelMode.STEREO, pcmFormat = PcmFormat.PCM_16,
                    packetMs = 30, jitterMs = 80, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.DEFAULT,
                    bufferMs = 20, maxSensitivity = false, rawBypass = false, digitalGainDb = 0.0),
                dsp = AudioConfig.DEFAULT.copy(
                    inputGainDb = 6.0,
                    highPassEnabled = true, highPassHz = 80,
                    compressorEnabled = true,
                    compThresholdDb = -18.0, compRatio = 3.0,
                    limiterEnabled = true, limiterCeiling = 0.99,
                ),
                activePreset = "Clean Monitoring"
            ),
            builtIn = true
        ),
        Preset(
            name = "Speech Detail",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.UDP, sampleRate = 48000,
                    channelMode = ChannelMode.MONO, pcmFormat = PcmFormat.PCM_16,
                    packetMs = 20, jitterMs = 60, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.VOICE_COMMUNICATION,
                    bufferMs = 10, maxSensitivity = false, rawBypass = false, digitalGainDb = 12.0),
                dsp = AudioConfig.DEFAULT.copy(
                    inputGainDb = 12.0,
                    highPassEnabled = true, highPassHz = 80,
                    lowPassEnabled = true, lowPassHz = 7000,
                    notchEnabled = true, notchHz = 60.0, notchQ = 1.0,
                ),
                activePreset = "Speech Detail"
            ),
            builtIn = true
        ),
        Preset(
            name = "Raw / Unprocessed",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.UDP, sampleRate = 48000,
                    channelMode = ChannelMode.MONO, pcmFormat = PcmFormat.PCM_16,
                    packetMs = 30, jitterMs = 60, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.UNPROCESSED,
                    bufferMs = 15, maxSensitivity = true, rawBypass = true, digitalGainDb = 0.0),
                dsp = AudioConfig.DEFAULT,
                activePreset = "Raw / Unprocessed"
            ),
            builtIn = true
        ),
        Preset(
            name = "Stable Network",
            settings = AppSettings(
                network = NetworkConfig(transport = TransportType.TCP, sampleRate = 44100,
                    channelMode = ChannelMode.MONO, pcmFormat = PcmFormat.PCM_16,
                    packetMs = 40, jitterMs = 120, autoReconnect = true),
                capture = CaptureConfig(inputSource = InputSource.DEFAULT,
                    bufferMs = 20, maxSensitivity = false, rawBypass = false, digitalGainDb = 0.0),
                dsp = AudioConfig.DEFAULT.copy(
                    inputGainDb = 6.0,
                    highPassEnabled = true, highPassHz = 80,
                ),
                activePreset = "Stable Network"
            ),
            builtIn = true
        ),
    )

    fun builtInByName(name: String): Preset? = BUILT_IN.find { it.name == name }
    val names: List<String> = BUILT_IN.map { it.name }
}
