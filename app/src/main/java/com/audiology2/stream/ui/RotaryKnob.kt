package com.audiology2.stream.ui

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.audiology2.stream.R
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Studio-style rotary knob control for audio parameters.
 * Supports continuous rotation with configurable range, step, and visual feedback.
 */
class RotaryKnob @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val paintProgress = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
    private val paintMarker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textAlign = Paint.Align.CENTER; textSize = 28f }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; textAlign = Paint.Align.CENTER; textSize = 14f }

    private var minValue = 0f
    private var maxValue = 100f
    private var _currentValue = 0f
    private var step = 1f
    private var startAngle = -135f // degrees
    private var sweepAngle = 270f // degrees
    private var knobRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var lastTouchAngle = 0f
    private var lastValue = 0f
    private var isDragging = false

    // Callbacks
    private var onValueChangeListener: ((Float) -> Unit)? = null

    val value: Float
        get() = _currentValue

    init {
        attrs?.let { a ->
            val ta = context.obtainStyledAttributes(a, R.styleable.RotaryKnob)
            minValue = ta.getFloat(R.styleable.RotaryKnob_minValue, 0f)
            maxValue = ta.getFloat(R.styleable.RotaryKnob_maxValue, 100f)
            _currentValue = ta.getFloat(R.styleable.RotaryKnob_value, 0f)
            step = ta.getFloat(R.styleable.RotaryKnob_step, 1f)
            startAngle = ta.getFloat(R.styleable.RotaryKnob_startAngle, -135f)
            sweepAngle = ta.getFloat(R.styleable.RotaryKnob_sweepAngle, 270f)
            ta.recycle()
        }
        _currentValue = _currentValue.coerceIn(minValue, maxValue)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h).toFloat()
        val padding = 24f
        knobRadius = (size - padding * 2) / 2
        centerX = w / 2f
        centerY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Track arc
        val rect = RectF(
            centerX - knobRadius,
            centerY - knobRadius,
            centerX + knobRadius,
            centerY + knobRadius
        )
        paintTrack.color = 0xFF333333.toInt()
        canvas.drawArc(rect, startAngle, sweepAngle, false, paintTrack)

        // Progress arc
        val progress = (_currentValue - minValue) / (maxValue - minValue)
        val progressAngle = startAngle + progress * sweepAngle
        paintProgress.color = 0xFF00D4AA.toInt() // Teal accent
        canvas.drawArc(rect, startAngle, progressAngle - startAngle, false, paintProgress)

        // Center knob indicator
        paintMarker.color = 0xFF00D4AA.toInt()
        val indicatorRadius = knobRadius * 0.12f
        val indicatorAngle = (progressAngle - 90) * PI / 180
        val markerX = centerX + (knobRadius * 0.65f) * kotlin.math.cos(indicatorAngle.toDouble()).toFloat()
        val markerY = centerY + (knobRadius * 0.65f) * kotlin.math.sin(indicatorAngle.toDouble()).toFloat()
        canvas.drawCircle(markerX, markerY, indicatorRadius, paintMarker)

        // Center value text
        val displayValue = if (step >= 1f) _currentValue.toInt().toString() else String.format("%.1f", _currentValue)
        paintValue.color = 0xFFFFFFFF.toInt()
        canvas.drawText(displayValue, centerX, centerY + paintValue.textSize / 3, paintValue)

        // Label below
        if (contentDescription.isNotEmpty()) {
            paintLabel.color = 0xFF888888.toInt()
            canvas.drawText(contentDescription.toString(), centerX, centerY + knobRadius + 30f, paintLabel)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val angle = angleFromTouch(event.x, event.y)
                if (angle != null) {
                    lastTouchAngle = angle
                    lastValue = _currentValue
                    isDragging = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val angle = angleFromTouch(event.x, event.y)
                    if (angle != null) {
                        val delta = angle - lastTouchAngle
                        // Handle wrap-around
                        val adjustedDelta = when {
                            delta > 180 -> delta - 360
                            delta < -180 -> delta + 360
                            else -> delta
                        }
                        val valuePerDegree = ((maxValue - minValue) / sweepAngle).toFloat()
                        var newValue = lastValue + adjustedDelta * valuePerDegree
                        newValue = max(minValue, min(maxValue, newValue))
                        // Snap to step
                        if (step > 0) {
                            newValue = (newValue / step).roundToInt() * step
                        }
                        if (newValue != _currentValue) {
                            _currentValue = newValue
                            onValueChangeListener?.invoke(_currentValue)
                            invalidate()
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun angleFromTouch(x: Float, y: Float): Float? {
        val dx = x - centerX
        val dy = y - centerY
        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        // Only respond to touches within the knob area (with some margin)
        if (dist < knobRadius * 1.5) {
            var angle = (kotlin.math.atan2(dy.toDouble(), dx.toDouble()) * 180 / PI).toFloat()
            if (angle < 0) angle += 360
            return angle
        }
        return null
    }

    fun setValue(value: Float) {
        _currentValue = value.coerceIn(minValue, maxValue)
        invalidate()
    }

    fun setOnValueChangeListener(listener: (Float) -> Unit) {
        onValueChangeListener = listener
    }

    companion object {
        // XML attribute constants
        const val ATTR_MIN_VALUE = "minValue"
        const val ATTR_MAX_VALUE = "maxValue"
        const val ATTR_VALUE = "value"
        const val ATTR_STEP = "step"
        const val ATTR_START_ANGLE = "startAngle"
        const val ATTR_SWEEP_ANGLE = "sweepAngle"
    }
}