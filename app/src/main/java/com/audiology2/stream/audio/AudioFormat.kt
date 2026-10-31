package com.audiology2.stream.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.audiology2.stream.config.InputSource
import com.audiology2.stream.config.PcmFormat

/**
 * Maps between the high-level config enums and the Android audio constants.
 * Also queries what the device actually supports so the UI can validate.
 */
object AudioFormat {

    fun inputSourceToAudioSource(src: InputSource): Int = when (src) {
        InputSource.DEFAULT -> MediaRecorder.AudioSource.DEFAULT
        InputSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        InputSource.VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
        InputSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        InputSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        InputSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        // Playback capture does not use a MediaRecorder source (it builds an
        // AudioRecord via AudioPlaybackCaptureConfiguration); value unused.
        InputSource.PLAYBACK -> MediaRecorder.AudioSource.DEFAULT
    }

    /** Android AudioFormat encoding for the capture path. */
    fun wireToCaptureEncoding(fmt: PcmFormat, forceFloat: Boolean): Int {
        if (forceFloat) return AudioFormat.ENCODING_PCM_FLOAT
        return when (fmt) {
            PcmFormat.PCM_8 -> AudioFormat.ENCODING_PCM_8BIT
            PcmFormat.PCM_16 -> AudioFormat.ENCODING_PCM_16BIT
            PcmFormat.PCM_24 -> AudioFormat.ENCODING_PCM_FLOAT
            PcmFormat.PCM_32 -> AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    fun channelConfig(channels: Int): Int = when (channels) {
        1 -> AudioFormat.CHANNEL_IN_MONO
        2 -> AudioFormat.CHANNEL_IN_STEREO
        else -> AudioFormat.CHANNEL_IN_MONO
    }

    /** Returns true if this device reports float capture as supported at [rate]. */
    fun supportsFloat(rate: Int): Boolean {
        val sz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return sz > 0 && sz != AudioRecord.ERROR && sz != AudioRecord.ERROR_BAD_VALUE
    }

    /** Returns true if this device reports [encoding] as supported at [rate]. */
    fun supportsEncoding(rate: Int, encoding: Int): Boolean {
        val sz = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
        return sz > 0 && sz != AudioRecord.ERROR && sz != AudioRecord.ERROR_BAD_VALUE
    }

    /** Sorted list of sample rates the device supports for 16-bit mono capture. */
    fun supportedRates16(): List<Int> {
        val rates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 176400, 192000)
        return rates.filter { supportsEncoding(it, AudioFormat.ENCODING_PCM_16BIT) }
    }

    /** Recommended minimum AudioRecord buffer size in bytes. */
    fun minBufferSizeBytes(rate: Int, encoding: Int, channels: Int): Int {
        val sz = AudioRecord.getMinBufferSize(rate, channelConfig(channels), encoding)
        return if (sz > 0) sz else 0
    }

    /** Convert 16-bit shorts to normalized floats in [-1, 1]. */
    fun shortsToFloat(src: ShortArray, dst: FloatArray, frames: Int, channels: Int) {
        val scale = 1.0f / 32768.0f
        var i = 0
        while (i < frames * channels) {
            dst[i] = src[i] * scale
            i++
        }
    }
}
