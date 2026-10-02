package com.futurethinking.timestampgenius.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.futurethinking.timestampgenius.LayoutConfig
import com.futurethinking.timestampgenius.LineShape
import kotlin.math.abs
import kotlin.math.roundToInt

class LineEditorView(
    context: Context,
    initial: LayoutConfig,
    private val onSave: (LayoutConfig) -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private var config = initial.copy(
        lines = initial.lines.map { it.copy() }.toMutableList()
    )

    private var selectedLine = 0
    private var touchMode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var startWidth = 0f
    private var startHeight = 0f
    private var moved = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textSize = dp(16f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x55000000)

        val left = config.boxLeft * width
        val top = config.boxTop * height
        val right = (config.boxLeft + config.boxWidth).coerceAtMost(1f) * width
        val bottom = (config.boxTop + config.boxHeight).coerceAtMost(1f) * height

        stroke.color = Color.YELLOW
        stroke.strokeWidth = dp(3f)
        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            dp(10f),
            dp(10f),
            stroke
        )

        val gap = (bottom - top) / config.lineCount.coerceAtLeast(1)

        for (index in 0 until config.lineCount) {
            val shape = config.lines[index]
            val centerY = top + gap * index
            val lineHeight = gap * 0.72f * shape.heightFraction
            val lineWidth = (right - left) * shape.widthFraction
            val x = left + ((right - left) - lineWidth) / 2f
            val y = centerY + (gap - lineHeight) / 2f

            stroke.strokeWidth = if (index == selectedLine) dp(3.5f) else dp(2f)
            stroke.color = if (index == selectedLine || shape.unlocked) {
                Color.YELLOW
            } else {
                Color.WHITE
            }

            val radius = dp(6f + shape.cornerFraction * 20f)
            canvas.drawRoundRect(
                x,
                y,
                x + lineWidth,
                y + lineHeight,
                radius,
                radius,
                stroke
            )

            textPaint.textSize = dp(16f)
            canvas.drawText(
                (index + 1).toString(),
                left + dp(8f),
                y + lineHeight / 2f + dp(6f),
                textPaint
            )
        }

        drawControls(canvas)
    }

    private fun drawControls(canvas: Canvas) {
        val panelHeight = dp(168f)
        val panelTop = height - panelHeight
        val side = dp(8f)
        val gap = dp(4f)

        fill.color = Color.WHITE
        canvas.drawRect(0f, panelTop, width.toFloat(), height.toFloat(), fill)

        textPaint.textSize = dp(13f)
        canvas.drawText(
            "LINES " + config.lineCount,
            side,
            panelTop + dp(24f),
            textPaint
        )
        canvas.drawText(
            "SPEED " + config.scrollSpeed,
            dp(102f),
            panelTop + dp(24f),
            textPaint
        )
        canvas.drawText(
            "LINE " + (selectedLine + 1),
            dp(202f),
            panelTop + dp(24f),
            textPaint
        )

        button(canvas, side, panelTop + dp(38f), dp(48f), dp(44f), "-")
        button(canvas, dp(60f), panelTop + dp(38f), dp(48f), dp(44f), "+")
        for (speed in 0..9) {
            val left = dp(112f) + speed * dp(32f)
            button(canvas, left, panelTop + dp(38f), dp(28f), dp(44f), speed.toString())
        }
        button(
            canvas,
            width.toFloat() - dp(88f),
            panelTop + dp(38f),
            dp(80f),
            dp(44f),
            "LOCK"
        )

        button(canvas, side, panelTop + dp(92f), dp(62f), dp(46f), "W-")
        button(canvas, dp(74f), panelTop + dp(92f), dp(62f), dp(46f), "W+")
        button(canvas, dp(140f), panelTop + dp(92f), dp(62f), dp(46f), "H-")
        button(canvas, dp(206f), panelTop + dp(92f), dp(62f), dp(46f), "H+")
        button(canvas, dp(272f), panelTop + dp(92f), dp(78f), dp(46f), "CORNER")
        button(canvas, dp(354f), panelTop + dp(92f), dp(72f), dp(46f), "SAVE")
        button(
            canvas,
            dp(430f),
            panelTop + dp(92f),
            (width - dp(438f)).coerceAtLeast(dp(80f)),
            dp(46f),
            "CLOSE"
        )
    }

    private fun button(
        canvas: Canvas,
        left: Float,
        top: Float,
        buttonWidth: Float,
        buttonHeight: Float,
        label: String
    ) {
        val right = minOf(width.toFloat() - dp(4f), left + buttonWidth)

        stroke.color = Color.BLACK
        stroke.strokeWidth = dp(1.5f)
        canvas.drawRect(left, top, right, top + buttonHeight, stroke)

        textPaint.textSize = dp(10f)
        val textWidth = textPaint.measureText(label)
        canvas.drawText(
            label,
            left + (right - left - textWidth) / 2f,
            top + buttonHeight / 2f + dp(4f),
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                startLeft = config.boxLeft
                startTop = config.boxTop
                startWidth = config.boxWidth
                startHeight = config.boxHeight
                moved = false
                touchMode = hitTest(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > dp(8f) || abs(event.y - downY) > dp(8f)) {
                    moved = true
                }

                when (touchMode) {
                    MODE_MOVE -> {
                        config.boxLeft = (
                            startLeft + (event.x - downX) / width
                        ).coerceIn(0f, 1f - config.boxWidth)

                        config.boxTop = (
                            startTop + (event.y - downY) / height
                        ).coerceIn(0f, 1f - config.boxHeight)
                    }

                    MODE_RESIZE_WIDTH -> {
                        config.boxWidth = (
                            startWidth + (event.x - downX) / width
                        ).coerceIn(0.25f, 0.95f)
                        config.boxLeft =
                            config.boxLeft.coerceAtMost(1f - config.boxWidth)
                    }

                    MODE_RESIZE_HEIGHT -> {
                        config.boxHeight = (
                            startHeight + (event.y - downY) / height
                        ).coerceIn(0.18f, 0.85f)
                        config.boxTop =
                            config.boxTop.coerceAtMost(1f - config.boxHeight)
                    }
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (touchMode == MODE_CONTROLS) {
                    handleControl(event.x, event.y)
                } else if (touchMode == MODE_MOVE && !moved) {
                    selectLine(event.y)
                }
                return true
            }
        }

        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        if (y > height - dp(180f)) return MODE_CONTROLS

        val left = config.boxLeft * width
        val top = config.boxTop * height
        val right = (config.boxLeft + config.boxWidth) * width
        val bottom = (config.boxTop + config.boxHeight) * height

        return when {
            x in (right - dp(40f))..(right + dp(40f)) &&
                y in top..bottom -> MODE_RESIZE_WIDTH

            y in (bottom - dp(40f))..(bottom + dp(40f)) &&
                x in left..right -> MODE_RESIZE_HEIGHT

            x in left..right && y in top..bottom -> MODE_MOVE
            else -> MODE_CONTROLS
        }
    }

    private fun selectLine(y: Float) {
        val top = config.boxTop * height
        val gap = config.boxHeight * height / config.lineCount.coerceAtLeast(1)
        selectedLine = ((y - top) / gap)
            .toInt()
            .coerceIn(0, config.lineCount - 1)
        invalidate()
    }

    private fun handleControl(x: Float, y: Float) {
        val panelTop = height - dp(168f)

        if (y < panelTop + dp(32f)) return

        if (y < panelTop + dp(86f)) {
            when {
                x <= dp(52f) -> setCount(config.lineCount - 1)
                x <= dp(106f) -> setCount(config.lineCount + 1)
                x in dp(112f)..dp(432f) -> {
                    config.scrollSpeed =
                        ((x - dp(112f)) / dp(32f))
                            .toInt()
                            .coerceIn(0, 9)
                    invalidate()
                }
                x >= width - dp(100f) -> {
                    config.lines[selectedLine].unlocked =
                        !config.lines[selectedLine].unlocked
                    invalidate()
                }
            }
            return
        }

        when {
            x <= dp(68f) -> editSelected(-0.08f, 0f)
            x <= dp(134f) -> editSelected(0.08f, 0f)
            x <= dp(200f) -> editSelected(0f, -0.08f)
            x <= dp(266f) -> editSelected(0f, 0.08f)
            x <= dp(350f) -> {
                val current = config.lines[selectedLine].cornerFraction
                config.lines[selectedLine].cornerFraction =
                    if (current > 0.5f) 0f else 1f
                invalidate()
            }
            x <= dp(426f) -> onSave(
                config.copy(lines = config.lines.map { it.copy() }.toMutableList())
            )
            else -> onClose()
        }
    }

    private fun setCount(value: Int) {
        config.lineCount = value.coerceIn(1, 50)

        while (config.lines.size < config.lineCount) {
            config.lines += LineShape()
        }

        while (config.lines.size > config.lineCount) {
            config.lines.removeAt(config.lines.lastIndex)
        }

        selectedLine = selectedLine.coerceIn(0, config.lineCount - 1)
        invalidate()
    }

    private fun editSelected(widthDelta: Float, heightDelta: Float) {
        val shape = config.lines[selectedLine]

        if (!shape.unlocked) return

        shape.widthFraction =
            (shape.widthFraction + widthDelta).coerceIn(0.4f, 1f)

        shape.heightFraction =
            (shape.heightFraction + heightDelta).coerceIn(0.35f, 1.4f)

        invalidate()
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_MOVE = 1
        private const val MODE_RESIZE_WIDTH = 2
        private const val MODE_RESIZE_HEIGHT = 3
        private const val MODE_CONTROLS = 10
    }
}
