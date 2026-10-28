package com.audiology2.stream.dsp

import kotlin.math.sin

/**
 * Real-time insert effects used inside [ProcessingChain] (the live
 * monitoring/recording path). Each implements [AudioProcessor] so they sit
 * alongside the existing filters/dynamics stages.
 */

/** Feedback delay (echo) with a per-channel ring buffer. */
class EchoProcessor(
    private var delayMs: Double = 320.0,
    private var feedback: Double = 0.35,
    private var mix: Double = 0.25
) : AudioProcessor {
    private var sampleRate = 48000
    private var channels = 1
    private var ring: FloatArray = FloatArray(0)
    private var writePos = 0
    private var delayFrames = 0
    private var fb = 0.0
    private var wet = 0.0

    override fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceAtLeast(1)
        delayFrames = (delayMs / 1000.0 * sampleRate).toInt().coerceIn(50, sampleRate * 4)
        ring = FloatArray(delayFrames * this.channels)
        writePos = 0
        fb = feedback.coerceIn(0.0, 0.95)
        wet = mix.coerceIn(0.0, 1.0)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        if (ring.isEmpty() || frameCount <= 0) return
        var idx = offset
        for (f in 0 until frameCount) {
            val readPosFrames = (writePos - delayFrames).mod(delayFrames)
            for (c in 0 until channels) {
                val rBase = readPosFrames * channels + c
                val wBase = writePos * channels + c
                val delayed = ring[rBase]
                val input = buffer[idx]
                ring[wBase] = (input + delayed * fb).toFloat().coerceIn(-1f, 1f)
                buffer[idx] = (input * (1.0 - wet) + delayed * wet).toFloat()
                idx++
            }
            writePos++
            if (writePos >= delayFrames) writePos = 0
        }
    }
}

/** Soft-clipping harmonic distortion (tanh curve). Stateless. */
class DistortionProcessor(
    private var drive: Double = 10.0
) : AudioProcessor {
    private var k = 1.0
    private var tanhK = 1.0
    private var channels = 1

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels.coerceAtLeast(1)
        k = (drive.coerceIn(0.0, 30.0) / 30.0) * 24.0 + 0.2
        tanhK = kotlin.math.tanh(k)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        var idx = offset
        for (f in 0 until frameCount) {
            for (c in 0 until channels) {
                buffer[idx] = (kotlin.math.tanh(buffer[idx] * k) / tanhK).toFloat().coerceIn(-1f, 1f)
                idx++
            }
        }
    }
}

/** Amplitude tremolo with a depth-scaled sine LFO. */
class TremoloProcessor(
    private var rateHz: Double = 5.0,
    private var depth: Double = 0.6
) : AudioProcessor {
    private var sampleRate = 48000
    private var channels = 1
    private var phase = 0L
    private var rate = 5.0
    private var d = 0.6

    override fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels.coerceAtLeast(1)
        phase = 0L
        rate = rateHz.coerceIn(0.1, 30.0)
        d = depth.coerceIn(0.0, 1.0)
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        var idx = offset
        for (f in 0 until frameCount) {
            val mod = 1.0 - d * (0.5 - 0.5 * sin(2.0 * kotlin.math.PI * rate * phase / sampleRate))
            val gain = mod.toFloat()
            for (c in 0 until channels) {
                buffer[idx] = buffer[idx] * gain
                idx++
            }
            phase++
        }
    }
}
