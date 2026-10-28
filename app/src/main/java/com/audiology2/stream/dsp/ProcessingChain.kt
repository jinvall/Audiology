package com.audiology2.stream.dsp

/**
 * Ordered chain of [AudioProcessor] stages with global bypass (A/B).
 * The processing order is: gain → agc → noise-suppress → hpf → lpf → notch → eq
 * → echo → distortion → tremolo → compressor → limiter. Presets can toggle
 * individual stages and the global [bypass] flag (A/B comparison against the
 * raw path).
 */
class ProcessingChain(
    sampleRate: Int,
    private val channels: Int
) {
    enum class Stage(val build: (AudioConfig, Int, Int) -> AudioProcessor?) {
        GAIN({ cfg, sr, ch -> GainProcessor(cfg.inputGainDb) }),
        AGC({ cfg, sr, ch -> if (!cfg.agcEnabled) null else AgcProcessor() }),
        NOISE_SUPPRESS({ cfg, sr, ch -> if (!cfg.nsEnabled) null else NoiseSuppressor() }),
        HIGH_PASS({ cfg, sr, ch -> if (!cfg.highPassEnabled) null else HighPassFilter(cfg.highPassHz.toDouble(), sr.toDouble()) }),
        LOW_PASS({ cfg, sr, ch -> if (!cfg.lowPassEnabled) null else LowPassFilter(cfg.lowPassHz.toDouble(), sr.toDouble()) }),
        NOTCH({ cfg, sr, ch -> if (!cfg.notchEnabled) null else NotchFilter(cfg.notchHz, cfg.notchQ, sr.toDouble()) }),
        EQ({ cfg, sr, ch -> if (!cfg.eqEnabled) null else Equalizer(cfg.eqHz.toDouble(), cfg.eqGainDb, cfg.eqBwOctaves, sr.toDouble()) }),
        ECHO({ cfg, sr, ch -> if (!cfg.echoEnabled) null else EchoProcessor(cfg.echoDelayMs, cfg.echoFeedback, cfg.echoMix) }),
        DISTORTION({ cfg, sr, ch -> if (!cfg.distortionEnabled) null else DistortionProcessor(cfg.distortionDrive) }),
        TREMOLO({ cfg, sr, ch -> if (!cfg.tremoloEnabled) null else TremoloProcessor(cfg.tremoloRateHz, cfg.tremoloDepth) }),
        COMPRESSOR({ cfg, sr, ch -> if (!cfg.compressorEnabled) null else Compressor(cfg.compThresholdDb, cfg.compRatio, cfg.compAttackMs, cfg.compReleaseMs, cfg.makeUpGainDb) }),
        LIMITER({ cfg, sr, ch -> if (!cfg.limiterEnabled) null else Limiter(cfg.limiterCeiling, cfg.limiterReleaseMs) });

        val order: Int get() = ordinal
    }

    private val sr = sampleRate
    private var stages: List<AudioProcessor> = emptyList()

    /** Config held by the chain; replaced when settings change. */
    var currentConfig: AudioConfig = AudioConfig.DEFAULT
        set(v) {
            field = v
            rebuild()
        }

    /** Bypass flag (A/B). When true, [process] is a no-op. */
    var bypass: Boolean = false

    init { rebuild() }

    private fun rebuild() {
        val cfg = currentConfig
        stages = Stage.entries
            .sortedBy { it.order }
            .mapNotNull { runCatching { it.build(cfg, sr, channels) }.getOrNull() }
            .filterNotNull()
            .onEach { it.configure(sr, channels) }
        // If a stage failed to construct (bad params), log via stderr for debug.
    }

    fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        if (bypass) return
        for (s in stages) s.process(buffer, offset, frameCount)
    }
}
