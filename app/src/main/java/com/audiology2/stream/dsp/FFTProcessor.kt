package com.audiology2.stream.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simple Cooley-Tukey FFT implementation for spectrogram generation.
 * No external dependencies - pure Kotlin implementation.
 */
class FFTProcessor(
    val fftSize: Int = 2048,
    private val sampleRate: Int = 48000,
    private var overlapPercent: Float = 0.75f // 75% overlap
) {

    // Pre-computed twiddle factors
    private val cosTable = DoubleArray(fftSize)
    private val sinTable = DoubleArray(fftSize)
    private val revBits = IntArray(fftSize)
    
    private val window = DoubleArray(fftSize)
    private val realData = DoubleArray(fftSize)
    private val imagData = DoubleArray(fftSize)
    private val magnitude = FloatArray(fftSize / 2)
    
    private var hopSize: Int = (fftSize * (1f - overlapPercent)).toInt()
    private var inputBuffer = DoubleArray(fftSize)
    private var bufferPos = 0

    init {
        // Pre-compute twiddle factors
        for (i in 0 until fftSize) {
            val angle = -2.0 * PI * i / fftSize
            cosTable[i] = cos(angle)
            sinTable[i] = sin(angle)
        }
        
        // Bit-reversal permutation
        var j = 0
        for (i in 1 until fftSize) {
            var bit = fftSize shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            revBits[i] = j
        }
        
        // Hann window
        for (i in 0 until fftSize) {
            window[i] = 0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))
        }
    }

    /** Process audio frames and return spectrogram data when ready.
     * Returns null if not enough data for a new FFT frame. */
    fun process(samples: FloatArray, numFrames: Int, channels: Int): Array<FloatArray>? {
        val frames = mutableListOf<FloatArray>()
        
        for (i in 0 until numFrames) {
            // Mix to mono if stereo
            var sample = 0f
            for (ch in 0 until channels) {
                sample += samples[i * channels + ch]
            }
            sample /= channels.toFloat()
            
            inputBuffer[bufferPos] = sample.toDouble() * window[bufferPos]
            bufferPos++
            
            if (bufferPos >= fftSize) {
                // Compute FFT
                val frame = computeFFT()
                frames.add(frame)
                
                // Shift buffer by hop size (overlap)
                val remaining = fftSize - hopSize
                for (j in 0 until remaining) {
                    inputBuffer[j] = inputBuffer[j + hopSize]
                }
                bufferPos = remaining
            }
        }
        
        return if (frames.isNotEmpty()) frames.toTypedArray() else null
    }

    private fun computeFFT(): FloatArray {
        // Copy to FFT arrays with bit-reversal
        for (i in 0 until fftSize) {
            val rev = revBits[i]
            realData[rev] = inputBuffer[i]
            imagData[rev] = 0.0
        }
        
        // Iterative Cooley-Tukey FFT
        var len = 2
        while (len <= fftSize) {
            val halfLen = len shr 1
            val step = fftSize / len
            for (i in 0 until fftSize step len) {
                for (j in 0 until halfLen) {
                    val u = i + j
                    val v = u + halfLen
                    
                    val twiddle = j * step
                    val cosT = cosTable[twiddle]
                    val sinT = sinTable[twiddle]
                    
                    val reV = realData[v]
                    val imV = imagData[v]
                    
                    val reT = reV * cosT - imV * sinT
                    val imT = reV * sinT + imV * cosT
                    
                    realData[v] = realData[u] - reT
                    imagData[v] = imagData[u] - imT
                    realData[u] += reT
                    imagData[u] += imT
                }
            }
            len = len shl 1
        }
        
        // Compute magnitude spectrum (only first half for real signal)
        for (i in 0 until fftSize / 2) {
            val re = realData[i]
            val im = imagData[i]
            val mag = sqrt(re * re + im * im)
            
            // Convert to dB, normalize to 0-1
            val db = if (mag > 1e-10) 20.0 * log10(mag) else -200.0
            magnitude[i] = ((db + 120.0) / 120.0).coerceIn(0.0, 1.0).toFloat()
        }
        
        return magnitude.copyOf()
    }

    fun setOverlap(percent: Float) {
        overlapPercent = percent.coerceIn(0f, 0.95f)
        hopSize = (fftSize * (1f - overlapPercent)).toInt()
    }

    companion object {
        const val PI = 3.141592653589793
    }
}

/** Spectrogram frame for time-frequency display */
data class SpectrogramFrame(
    val magnitudes: FloatArray,  // Size = fftSize/2, normalized 0-1
    val timestampMs: Long,
    val sampleRate: Int,
    val fftSize: Int
) {
    val frequencyResolutionHz: Double get() = sampleRate.toDouble() / fftSize
    val nyquistHz: Double get() = sampleRate.toDouble() / 2.0
}