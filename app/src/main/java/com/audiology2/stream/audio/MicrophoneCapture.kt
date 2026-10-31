package com.audiology2.stream.audio

import android.media.AudioRecord
import com.audiology2.stream.config.PcmFormat
import com.audiology2.stream.dsp.AudioConfig
import com.audiology2.stream.dsp.GainProcessor
import com.audiology2.stream.dsp.Metering
import com.audiology2.stream.dsp.ProcessingChain
import com.audiology2.stream.network.PacketizedFrame
import com.audiology2.stream.network.Protocol
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real microphone capture pipeline.
 *
 * Responsibilities:
 *  - Own an [AudioRecord] configured from the requested sample rate / channels /
 *    encoding / input source, falling back cleanly when the device rejects a
 *    configuration.
 *  - Run a dedicated, allocation-light capture thread that reads audio, applies
 *    digital gain + the optional DSP chain, packetizes it into protocol datagrams,
 *    and hands each [PacketizedFrame] to the bounded [sink] queue (back-pressure
 *    drops oldest audio rather than stalling mic capture).
 *  - Maintain a [Metering] instance and periodic [CaptureStats] callbacks.
 *
 * The heavy DSP math is delegated to pure-Kotlin processors (no allocations in
 * the hot loop). Only [AudioRecord] ties this class to Android.
 */
class MicrophoneCapture(
    private val sink: BlockingQueue<PacketizedFrame>,
    private val statsListener: (CaptureStats) -> Unit,
    private val audioThreadErrorHandler: (Throwable) -> Unit,
    private val recordingCallback: ((FloatArray, Int, Int) -> Unit)? = null
) {

    data class Config(
        val sampleRate: Int,
        val channels: Int,
        val pcmFormat: PcmFormat,
        val audioSource: Int,
        val bufferMs: Int,
        val packetMs: Int,
        val maxPayloadBytes: Int,
        val digitalGainDb: Double,
        val rawBypass: Boolean,
        val dspConfig: AudioConfig,
        val sessionId: Int
    )

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private var stats = CaptureStats()
    private var sequence = 0L
    private var samplePosition = 0L
    // Decaying holds so CLIP/Active clear after the signal stops, instead of
    // latching on forever for the rest of the session.
    private var clipHoldUntilMs = 0L
    private var activeHoldUntilMs = 0L

        // Reusable hot-loop buffers (allocated once per (re)start).
        private var floatBuf: FloatArray = FloatArray(0)
        private var readShorts: ShortArray = ShortArray(0)
        private var payloadBuf: ByteArray = ByteArray(Protocol.MAX_PAYLOAD)
        private var framesPerRead = 0
        private var encodingBytesPerSample = 2
        private var useFloatCapture = false

    fun start(config: Config): Boolean {
        if (running.get()) return true
        val enc = when (config.pcmFormat) {
            PcmFormat.PCM_8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
            PcmFormat.PCM_16 -> android.media.AudioFormat.ENCODING_PCM_16BIT
            PcmFormat.PCM_24 -> android.media.AudioFormat.ENCODING_PCM_FLOAT
            PcmFormat.PCM_32 -> android.media.AudioFormat.ENCODING_PCM_FLOAT
        }
        useFloatCapture = (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT)
        // If float isn't actually supported, fall back to 16-bit capture.
        if (useFloatCapture && !AudioFormat.supportsFloat(config.sampleRate)) {
            useFloatCapture = false
            encodingBytesPerSample = 2
        } else {
            encodingBytesPerSample = when (enc) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                else -> 2
            }
        }
        val actualEnc = if (useFloatCapture) android.media.AudioFormat.ENCODING_PCM_FLOAT
        else when (config.pcmFormat) {
            PcmFormat.PCM_8 -> android.media.AudioFormat.ENCODING_PCM_8BIT
            else -> android.media.AudioFormat.ENCODING_PCM_16BIT
        }

        // Size the read frame to the packet, MTU-clamped, with a sane minimum.
        val bps = config.maxPayloadBytes
        val bytesPerFrame = config.channels * config.pcmFormat.bytesPerSample
        val wireBps = when (config.pcmFormat) {
            PcmFormat.PCM_8 -> 1; PcmFormat.PCM_16 -> 2; PcmFormat.PCM_24 -> 3; PcmFormat.PCM_32 -> 4
        }
        val maxSamples = if (wireBps > 0) bps / wireBps else 960
        val requested = (config.sampleRate * config.packetMs + 999) / 1000
        val perPacket = minOf(requested, maxSamples).coerceAtLeast(64)
        framesPerRead = perPacket

        val minBufBytes = AudioFormat.minBufferSizeBytes(config.sampleRate, actualEnc, config.channels)
        if (minBufBytes == 0) {
            postError("Device does not support ${config.sampleRate}Hz/${config.pcmFormat}")
            return false
        }
        // AudioRecord buffer must be >= min. Use min but at least enough for one read.
        val readBytes = perPacket * config.channels * encodingBytesPerSample
        val bufBytes = maxOf(minBufBytes, readBytes)
        val ar = try {
            AudioRecord(
                config.audioSource, config.sampleRate,
                AudioFormat.channelConfig(config.channels),
                actualEnc, bufBytes
            )
        } catch (e: Exception) {
            postError("AudioRecord init failed: ${e.message}")
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            postError("AudioRecord not initialized")
            return false
        }
        audioRecord = ar
        sequence = 0L
        samplePosition = 0L
        stats = CaptureStats(
            sampleRate = config.sampleRate, channels = config.channels,
            pcmFormat = config.pcmFormat,
            encodingName = if (useFloatCapture) "PCM_FLOAT" else
                (if (actualEnc == android.media.AudioFormat.ENCODING_PCM_8BIT) "PCM_8" else "PCM_16"),
            bufferMs = config.bufferMs,
            effectivePacketMs = (perPacket * 1000 + config.sampleRate - 1) / config.sampleRate,
            running = true
        )

        val chain = ProcessingChain(config.sampleRate, config.channels).apply {
            currentConfig = config.dspConfig
            bypass = config.rawBypass || config.dspConfig.abBypass
        }
        val gain = GainProcessor.dbToLinear(config.digitalGainDb.coerceIn(-60.0, 60.0))
        val meter = Metering()

        floatBuf = FloatArray(framesPerRead * config.channels + 16)
        readShorts = ShortArray(framesPerRead * config.channels)
        var lastStatsTime = 0L

        running.set(true)
        thread = Thread {
            try {
                ar.startRecording()
                val payloadOffset = 0
                while (running.get() && ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val framesRead: Int
                    if (useFloatCapture) {
                        // read(float[], ...) returns number of FRAMES read.
                        framesRead = ar.read(floatBuf, 0, framesPerRead, AudioRecord.READ_BLOCKING)
                    } else {
                        // read(short[], ...) returns number of SAMPLES (shorts) read.
                        val samples = ar.read(readShorts, 0, framesPerRead * config.channels)
                        framesRead = if (samples > 0) samples / config.channels else samples
                        if (samples > 0) AudioFormat.shortsToFloat(readShorts, floatBuf, framesRead, config.channels)
                    }
                    if (framesRead <= 0) {
                        stats = stats.copy(readErrors = stats.readErrors + 1, running = true)
                        continue
                    }
                    samplePosition += framesRead.toLong()

                    // Apply digital preamp gain (no-op when 1.0).
                    if (gain != 1.0) {
                        for (i in 0 until framesRead * config.channels) {
                            var v = (floatBuf[i] * gain).toFloat()
                            if (v > 1.0f) v = 1.0f else if (v < -1.0f) v = -1.0f
                            floatBuf[i] = v
                        }
                    }
                    // DSP chain (bypass when raw).
                    chain.process(floatBuf, 0, framesRead)

                    // Metering on the (post-gain, pre-encode) signal.
                    meter.process(floatBuf, 0, framesRead, config.channels)

                    // Recording callback (pre-encode, post-DSP)
                    recordingCallback?.invoke(floatBuf, framesRead, config.channels)

                    // Encode + frame one packet.
                    val plen = PcmEncoder.encode(
                        floatBuf, payloadBuf, payloadOffset, framesRead, config.channels, config.pcmFormat
                    )
                    val packet = Protocol.build(
                        payloadType = when (config.pcmFormat) {
                            PcmFormat.PCM_8 -> Protocol.PayloadType.PCM_8
                            PcmFormat.PCM_16 -> Protocol.PayloadType.PCM_16
                            PcmFormat.PCM_24 -> Protocol.PayloadType.PCM_24
                            PcmFormat.PCM_32 -> Protocol.PayloadType.PCM_32
                        },
                        channels = config.channels,
                        sampleRate = config.sampleRate,
                        sessionId = config.sessionId,
                        sequence = sequence,
                        samplePosition = samplePosition - framesRead,
                        payload = payloadBuf,
                        payloadLen = plen
                    )
                    val frame = PacketizedFrame(
                        sessionId = config.sessionId,
                        sequence = sequence,
                        samplePosition = samplePosition - framesRead,
                        createdAtNs = System.nanoTime(),
                        packet = packet
                    )
                    sequence = (sequence + 1) % 4294967296L
                    if (!sink.offer(frame)) {
                        stats = stats.copy(dropped = stats.dropped + 1, running = true)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastStatsTime >= 250) {
                        lastStatsTime = now
                        // Decaying holds: show CLIP for ~800ms after a clipped
                        // window, Active for ~400ms. No permanent latch.
                        if (meter.isClipping) clipHoldUntilMs = now + 800L
                        if (meter.isActive) activeHoldUntilMs = now + 400L
                        stats = stats.copy(
                            rmsDb = meter.rmsDb, peakDb = meter.peakDb,
                            clipped = now < clipHoldUntilMs,
                            active = now < activeHoldUntilMs,
                            framesRead = samplePosition,
                            running = true
                        )
                        statsListener(stats)
                        // reset per-window metering accumulator
                        meter.reset()
                    }
                }
            } catch (e: Throwable) {
                running.set(false)
                stats = stats.copy(running = false)
                audioThreadErrorHandler(e)
            } finally {
                try { ar.stop() } catch (_: Exception) { }
                try { ar.release() } catch (_: Exception) { }
                audioRecord = null
                stats = stats.copy(running = false)
                statsListener(stats)
            }
        }
        thread?.start()
        return true
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        val t = thread
        if (t != null) {
            try { t.join(500) } catch (_: InterruptedException) { }
        }
        thread = null
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        audioRecord = null
        stats = stats.copy(running = false)
        statsListener(stats)
    }

    private fun postError(msg: String) {
        stats = stats.copy(error = msg, running = false)
        // statsListener carries the error string via the err field.
        statsListener(stats)
    }

    companion object
}
