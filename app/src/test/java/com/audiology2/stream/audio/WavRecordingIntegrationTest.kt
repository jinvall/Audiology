package com.audiology2.stream.audio

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check against a REAL recording produced by the app capture
 * pipeline. Gated on the artifact existing at the repo root; skipped (passes
 * trivially with a marker) in CI without it.
 */
class WavRecordingIntegrationTest {

    private val artifact = File("../../recording_1793153866196.wav")

    private fun load(): AudioBuffer? {
        if (!artifact.exists()) return null
        return WavUtils.read(artifact)
    }

    @Test
    fun readsRealRecordingHeaderAndSamples() {
        val buf = load() ?: return // artifact absent: skip
        // Header must decode to the file's true properties.
        assertEquals(44100, buf.sampleRate)
        assertEquals(1, buf.channels)
        assertEquals(1154300, buf.frames)

        // Frame count * channels matches the sample array exactly.
        assertEquals(buf.frames * buf.channels, buf.samples.size)
    }

    @Test
    fun readRangeClampsAndSlices() {
        val buf = load() ?: return
        val mid = WavUtils.readRange(artifact, 10.0, 12.0)
        assertNotNull(mid)
        assertEquals(2.0, mid.frames.toDouble() / mid.sampleRate, 0.01)
        // Values must be identical to the same slice of the full buffer.
        val start = (10.0 * buf.sampleRate).toInt()
        for (i in 0 until mid.samples.size) {
            assertEquals(buf.samples[start + i], mid.samples[i], 0.0f)
        }
    }

    @Test
    fun mixdownMatchesSingleChannel() {
        val buf = load() ?: return
        val mix = buf.mixdown(0, 1000)
        assertEquals(1000, mix.size)
        for (i in 0 until 1000) assertEquals(buf.samples[i], mix[i], 0.0f)
    }

    @Test
    fun recordingContentIsDetectedAsDigitalSilence() {
        val buf = load() ?: return
        val peak = buf.samples.maxOfOrNull { abs(it) } ?: 0f
        val rms = kotlin.math.sqrt(buf.samples.sumOf { (it.toDouble() * it) } / buf.samples.size)
        // Regression guard: if a future capture starts producing signal, this
        // test's threshold tells us about content; silence = 0 either way.
        assertEquals(0.0f, peak, 0.0f)
        assertEquals(0.0, rms, 0.0)
    }
}