package com.audiology2.stream.dsp

/**
 * Processing stage interface. All implementations are pure Kotlin (no Android
 * imports) so they can be exercised by host-side unit tests. Samples are
 * normalized floats in [-1, 1], interleaved by channel. Processing is in-place
 * on [buffer] over [frameCount] frames starting at [offset] (in frames).
 */
interface AudioProcessor {
    fun configure(sampleRate: Int, channels: Int)
    fun process(buffer: FloatArray, offset: Int, frameCount: Int)
}

/** Linear digital gain (preamplification). gainDb of 0 => gain 1.0. */
class GainProcessor(var gainDb: Double = 0.0) : AudioProcessor {
    private var gain = 1.0
    private var channels = 1

    override fun configure(sampleRate: Int, channels: Int) {
        this.channels = channels
        this.gain = dbToLinear(gainDb.coerceIn(-60.0, 60.0))
    }

    override fun process(buffer: FloatArray, offset: Int, frameCount: Int) {
        if (gain == 1.0) return
        val start = offset
        val end = offset + frameCount * channels
        for (i in start until end) {
            var v = (buffer[i] * gain).toFloat()
            if (v > 1.0f) v = 1.0f else if (v < -1.0f) v = -1.0f
            buffer[i] = v
        }
    }

    companion object {
        fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)
        fun linearToDb(linear: Double): Double = if (linear <= 0.0) -96.0 else 20.0 * Math.log10(linear)
    }

    init { configure(48000, 1) }
}
