package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Horizontal VU / level meter with a dB gradient, a peak-hold marker and a
 * clip LED. Feed it with [setLevel] (dBFS).
 */
class VuMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var label: String = ""
    var floorDb: Double = -60.0

    private var levelDb: Double = floorDb
    private var peakDb: Double = floorDb
    private var clipped = false

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF160F28.toInt() }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintPeak = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF3D2D61.toInt()
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF5A4191.toInt()
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFC8C9D4.toInt(); textSize = dp(10f)
    }

    private var gradient: LinearGradient? = null
    private var gradientWidth = 0f

    /** Update the meter. [peakHold] true keeps a decaying peak marker. */
    fun setLevel(db: Double, isClipped: Boolean = false) {
        levelDb = db
        // Peak-hold: snap up, ease down.
        peakDb = if (db >= peakDb) db else peakDb - 0.6
        if (peakDb < levelDb) peakDb = levelDb
        if (isClipped) clipped = true
        postInvalidateOnAnimation()
    }

    /** Clear the clip LED (call on user acknowledge/reset). */
    fun clearClip() {
        clipped = false
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((240 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((28 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(2f)
        val barTop = pad
        val barBottom = h - pad

        val bar = RectF(pad, barTop, w - pad, barBottom)
        canvas.drawRoundRect(bar, dp(3f), dp(3f), paintTrack)

        val radius = dp(3f)
        if (gradient == null || gradientWidth != bar.width()) {
            gradientWidth = bar.width()
            gradient = LinearGradient(
                0f, 0f, bar.width(), 0f,
                intArrayOf(
                    0xFF23D160.toInt(),   // green
                    0xFF12F012.toInt(),   // srp primary
                    0xFFFFB020.toInt(),   // amber
                    0xFFFF4D6D.toInt()    // danger
                ),
                floatArrayOf(0f, 0.55f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        paintFill.shader = gradient
        val levelFrac = ConsoleMath.dbToMeterFraction(levelDb, floorDb)
        if (levelFrac > 0f) {
            val fill = RectF(bar.left, bar.top, bar.left + bar.width() * levelFrac, bar.bottom)
            canvas.drawRoundRect(fill, radius, radius, paintFill)
        }

        // Peak-hold marker
        val peakFrac = ConsoleMath.dbToMeterFraction(peakDb, floorDb)
        if (peakFrac > 0.001f && peakFrac < 1f) {
            val x = bar.left + bar.width() * peakFrac
            canvas.drawRect(x - dp(1f), bar.top, x + dp(1f), bar.bottom, paintPeak)
        }

        canvas.drawRoundRect(bar, radius, radius, paintBorder)

        // dB ticks at -48,-36,-24,-12,-6,0
        for (t in intArrayOf(-48, -36, -24, -12, -6)) {
            val f = ConsoleMath.dbToMeterFraction(t.toDouble(), floorDb)
            val x = bar.left + bar.width() * f
            canvas.drawLine(x, bar.top, x, bar.top + dp(4f), paintTick)
        }

        if (label.isNotEmpty()) {
            canvas.drawText(label, bar.left + dp(4f), bar.bottom - dp(3f), paintText)
        }
        // Clip LED
        paintText.color = if (clipped) 0xFFFF4D6D.toInt() else 0xFF3A3355.toInt()
        canvas.drawCircle(w - dp(6f), h / 2f, dp(3f), paintText)
        paintText.color = 0xFFC8C9D4.toInt()
    }
}
