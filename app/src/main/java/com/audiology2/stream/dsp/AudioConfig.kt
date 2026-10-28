package com.audiology2.stream.dsp

import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of all DSP / capture settings that drive the
 * [ProcessingChain]. Kept dependency-free so the chain and its math can be
 * unit-tested on the JVM without Android.
 */
@Serializable
data class AudioConfig(
    val sampleRate: Int = 48000,
    val inputGainDb: Double = 0.0,
    /** Software AGC (when true, the chain applies soft AGC). */
    val agcEnabled: Boolean = false,
    /** Software noise suppression (spectral gate). */
    val nsEnabled: Boolean = false,
    /** A/B: when true the DSP chain is bypassed (raw comparison path). */
    val abBypass: Boolean = false,
    val highPassEnabled: Boolean = false,
    val highPassHz: Int = 80,
    val lowPassEnabled: Boolean = false,
    val lowPassHz: Int = 8000,
    val notchEnabled: Boolean = false,
    val notchHz: Double = 60.0,
    val notchQ: Double = 1.0,
    val eqEnabled: Boolean = false,
    val eqHz: Int = 1000,
    val eqGainDb: Double = 0.0,
    val eqBwOctaves: Double = 1.0,
    val compressorEnabled: Boolean = false,
    val compThresholdDb: Double = -24.0,
    val compRatio: Double = 4.0,
    val compAttackMs: Double = 5.0,
    val compReleaseMs: Double = 50.0,
    val makeUpGainDb: Double = 0.0,
    val limiterEnabled: Boolean = false,
    val limiterCeiling: Double = 1.0,
    val limiterReleaseMs: Double = 25.0,
    /** Real-time insert effects (live monitoring/recording path). */
    val echoEnabled: Boolean = false,
    val echoDelayMs: Double = 320.0,
    val echoFeedback: Double = 0.35,
    val echoMix: Double = 0.25,
    val distortionEnabled: Boolean = false,
    val distortionDrive: Double = 10.0,
    val tremoloEnabled: Boolean = false,
    val tremoloRateHz: Double = 5.0,
    val tremoloDepth: Double = 0.6
) {
    companion object {
        val DEFAULT = AudioConfig()
    }
}
