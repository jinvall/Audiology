package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * LED toggle for the console look: a pill track with a glowing indicator and a
 * label. Behaves like a switch (tap to toggle) and emits [onCheckedChanged].
 */
class LedSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var label: String = ""
    var onText: String = "ON"
    var offText: String = "OFF"

    /** Accent when ON (defaults to srp_primary green). */
    var accentOn: Int = 0xFF12F012.toInt()

    /** Dim color when OFF. */
    var accentOff: Int = 0xFF5A4191.toInt()

    var isChecked: Boolean = false
        set(v) {
            if (v != field) {
                field = v
                invalidate()
                onCheckedChanged?.invoke(v)
            }
        }

    var onCheckedChanged: ((Boolean) -> Unit)? = null

    /** Set state without firing the listener (syncing from settings). */
    fun setCheckedSilently(v: Boolean) {
        if (v != isChecked) {
            isChecked = v
            invalidate()
        }
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF3D2D61.toInt()
    }
    private val paintLed = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F3F7.toInt(); textSize = dp(11f); isFakeBoldText = true
    }

    init {
        isClickable = true
        // Read android:text from XML so it behaves like a labelled switch.
        attrs?.let { a ->
            val ta = context.obtainStyledAttributes(a, intArrayOf(android.R.attr.text))
            label = ta.getString(0) ?: ""
            ta.recycle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((150 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((34 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                isChecked = !isChecked
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(2f)
        val rect = RectF(pad, pad, w - pad, h - pad)
        val accent = if (isChecked) accentOn else accentOff

        paintTrack.color = if (isChecked) blend(0xFF1A1230.toInt(), accent, 0.18f) else 0xFF160F28.toInt()
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paintTrack)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paintBorder)

        // LED at left
        val ledX = pad + dp(12f)
        val ledY = h / 2f
        paintLed.color = accent
        // Glow halo
        paintGlow.color = accent
        paintGlow.alpha = if (isChecked) 60 else 20
        canvas.drawCircle(ledX, ledY, dp(7f), paintGlow)
        canvas.drawCircle(ledX, ledY, dp(4f), paintLed)

        // Label + state text
        val labelText = if (label.isNotEmpty()) label else (if (isChecked) onText else offText)
        paintText.color = if (isChecked) 0xFFF3F3F7.toInt() else 0xFF9698AB.toInt()
        val textBaseline = ledY - (paintText.descent() + paintText.ascent()) / 2f
        canvas.drawText(labelText, ledX + dp(11f), textBaseline, paintText)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
