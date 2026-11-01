package com.audiology2.stream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotary knob for the console look (gain, frequency, ratio, mix, ...).
 *
 * Drag VERTICALLY to change the value - this deliberately avoids the
 * radial-only drag that also let the surrounding scroll view steal the
 * gesture. The knob requests disallow-intercept while dragging so the page
 * never scrolls under it. Emits [onValueChanged].
 */
class KnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var minValue: Double = 0.0
    var maxValue: Double = 1.0
    var step: Double = 0.0
    var unit: String = ""

    var value: Double = 0.0
        set(v) {
            val c = ConsoleMath.clamp(v, minValue, maxValue)
            if (c != field) {
                field = c
                invalidate()
                onValueChanged?.invoke(c)
            }
        }

    var label: String = ""

    /** Accent color for the value arc + indicator (defaults to srp_primary green). */
    var accentColor: Int = 0xFF12F012.toInt()

    var onValueChanged: ((Double) -> Unit)? = null

    /** Set value without firing the listener (e.g. when syncing from settings). */
    fun setValueSilently(v: Double) {
        val c = ConsoleMath.clamp(v, minValue, maxValue)
        if (c != value) {
            value = c
            invalidate()
        }
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFF2A2140.toInt()
    }
    private val paintArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        strokeCap = Paint.Cap.ROUND
        color = accentColor
    }
    private val paintBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1230.toInt() }
    private val paintBodyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF3D2D61.toInt()
    }
    private val paintIndicator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND; color = accentColor
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0xFF5A4191.toInt()
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9698AB.toInt(); textAlign = Paint.Align.CENTER
        textSize = dp(10f)
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF3F3F7.toInt(); textAlign = Paint.Align.CENTER
        textSize = dp(13f); isFakeBoldText = true
    }

    private var dragging = false
    private var dragStartY = 0f
    private var dragStartValue = 0.0

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((80 * density).toInt(), widthMeasureSpec)
        val h = resolveSize((104 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartY = event.y
                dragStartValue = value
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                // Vertical drag: full height ~ 1.6x the range for a fine feel.
                val dy = dragStartY - event.y
                val range = maxValue - minValue
                val delta = (dy / (height.toFloat())).toDouble() * range * 1.6
                var next = dragStartValue + delta
                next = ConsoleMath.positionToValue(
                    ConsoleMath.valueToPosition(next, minValue, maxValue), minValue, maxValue, step
                )
                value = next
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
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
        val cx = w / 2f
        val radius = (minOf(w, h * 0.82f) / 2f - dp(10f)).coerceAtLeast(dp(12f))
        val cy = radius + dp(10f)

        val startAngle = 135f
        val sweep = 270f
        val pos = ConsoleMath.valueToPosition(value, minValue, maxValue)
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // Track + value arc
        canvas.drawArc(arcRect, startAngle, sweep, false, paintTrack)
        paintArc.color = accentColor
        canvas.drawArc(arcRect, startAngle, sweep * pos, false, paintArc)

        // Ticks (11 around the ring)
        paintTick.color = 0xFF5A4191.toInt()
        for (i in 0..10) {
            val a = Math.toRadians((startAngle + sweep * (i / 10f)).toDouble())
            val rOuter = radius + dp(7f)
            val rInner = radius + dp(3f)
            val x1 = cx + (rOuter * cos(a)).toFloat()
            val y1 = cy + (rOuter * sin(a)).toFloat()
            val x2 = cx + (rInner * cos(a)).toFloat()
            val y2 = cy + (rInner * sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paintTick)
        }

        // Knob body
        val bodyRadius = radius - dp(9f)
        canvas.drawCircle(cx, cy, bodyRadius, paintBody)
        canvas.drawCircle(cx, cy, bodyRadius, paintBodyEdge)

        // Indicator needle
        val a = Math.toRadians((startAngle + sweep * pos).toDouble())
        val ix1 = cx + (bodyRadius * 0.30f * cos(a)).toFloat()
        val iy1 = cy + (bodyRadius * 0.30f * sin(a)).toFloat()
        val ix2 = cx + (bodyRadius * 0.92f * cos(a)).toFloat()
        val iy2 = cy + (bodyRadius * 0.92f * sin(a)).toFloat()
        paintIndicator.color = accentColor
        canvas.drawLine(ix1, iy1, ix2, iy2, paintIndicator)

        // Label above, value below
        if (label.isNotEmpty()) {
            canvas.drawText(label, cx, dp(11f), paintLabel)
        }
        val valueText = formatValue(value) + if (unit.isNotEmpty()) unit else ""
        canvas.drawText(valueText, cx, h - dp(4f), paintValue)
    }

    private fun formatValue(v: Double): String {
        return when {
            step >= 1.0 -> v.toLong().toString()
            step >= 0.1 -> String.format("%.1f", v)
            else -> String.format("%.2f", v)
        }
    }
}
