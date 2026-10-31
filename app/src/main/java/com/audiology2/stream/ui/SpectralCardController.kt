package com.audiology2.stream.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.audiology2.stream.dsp.SpectralEngine
import androidx.appcompat.R
import kotlin.math.roundToInt

/**
 * Owns the Spectral Tools card: live analysis state + UI readouts.
 * Computation happens on the capture thread ([onFrame]); UI updates are
 * posted to the main thread with [pushToUi].
 *
 * The card is analysis + capture only. Spectral *editing* (STFT masks on
 * clips) lives in the spectral clip editor.
 */
class SpectralCardController(
    private val context: Context,
    private val pitchLabel: android.widget.TextView,
    private val tunerBar: android.widget.ProgressBar,
    private val peakLabel: android.widget.TextView,
    private val f0Label: android.widget.TextView,
    private val centroidLabel: android.widget.TextView,
    private val partialsLabel: android.widget.TextView
) {

    /** Immutable analysis result for one spectrum frame. */
    data class SpectralSnapshot(
        val noteName: String,
        val cents: Int,
        val midi: Int?,
        val peakFreq: Double,
        val peakDb: Double,
        val f0: Double?,
        val centroid: Double,
        val partialsText: String
    )

    @Volatile
    var snapshot: SpectralSnapshot? = null
        private set

    private var lastSpectrum: FloatArray? = null
    private var spectrumSampleRate = 48000
    private var spectrumFftSize = 2048
    private var lastSpectralUpdateMs = 0L

    /**
     * Feed a completed FFT frame set from the capture thread. Analysis runs
     * throttled (~6Hz); the card is only pushed when a new snapshot exists.
     */
    fun onFrame(frames: Array<FloatArray>, sampleRate: Int, fftSize: Int) {
        val now = System.currentTimeMillis()
        if (now - lastSpectralUpdateMs < 160) return
        lastSpectralUpdateMs = now
        lastSpectrum = frames.lastOrNull() ?: return
        spectrumSampleRate = sampleRate
        spectrumFftSize = fftSize
        snapshot = computeSnapshot()
        // Views must be touched on the main thread; this runs on the capture thread.
        pitchLabel.post { pushToUi() }
    }

    private fun computeSnapshot(): SpectralSnapshot {
        val frame = lastSpectrum ?: return SpectralSnapshot("--", 0, null, 0.0, -140.0, null, 0.0, "")
        val sr = spectrumSampleRate
        val fft = spectrumFftSize
        val peak = SpectralEngine.peak(frame, sr, fft)
        val f0 = SpectralEngine.fundamental(frame, sr, fft)
        val centroid = SpectralEngine.centroid(frame, sr, fft)
        val fForNote = f0 ?: peak?.freq
        val note = fForNote?.let { SpectralEngine.noteFromFreq(it) }
        val partials = SpectralEngine.partials(frame, sr, fft, topN = 6)
        val partialsText = partials.joinToString(" ") { "${it.freq.roundToInt()}Hz(${"%.0f".format(it.db)}dB)" }
        return SpectralSnapshot(
            noteName = note?.name ?: "--",
            cents = note?.cents ?: 0,
            midi = note?.midi,
            peakFreq = peak?.freq ?: 0.0,
            peakDb = peak?.db ?: -140.0,
            f0 = f0,
            centroid = centroid,
            partialsText = partialsText
        )
    }

    /** Push the latest snapshot into the card (main thread only). */
    fun pushToUi() {
        val s = snapshot ?: return
        pitchLabel.text = "Pitch: ${s.noteName}  (${if (s.cents >= 0) "+" else ""}${s.cents} cent)"
        tunerBar.progress = (50 + s.cents.coerceIn(-50, 50)).coerceIn(0, 100)
        peakLabel.text = "Peak: ${String.format("%.1f", s.peakFreq)} Hz at ${String.format("%.0f", s.peakDb)} dB"
        f0Label.text = "F0: ${s.f0?.let { String.format("%.1f", it) + " Hz" } ?: "--"}"
        centroidLabel.text = "Centroid: ${String.format("%.0f", s.centroid)} Hz"
        partialsLabel.text = "Partials: ${s.partialsText.ifEmpty { "--" }}"
    }

    /** Copy the peak to the system clipboard. */
    fun copyPeak() {
        val s = snapshot ?: run {
            Toast.makeText(context, "No live spectrum yet - start streaming", Toast.LENGTH_SHORT).show()
            return
        }
        val text = String.format("%.1f Hz (note %s)", s.peakFreq, s.noteName)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("peak", text))
        Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
    }
}
