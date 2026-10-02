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

class LineEditorView(
    context: Context,
    initial: LayoutConfig,
    private val onSave: (LayoutConfig) -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    private var config = initial.copy(lines = initial.lines.map { it.copy() }.toMutableList())
    private var selectedLine = 0
    private var touchMode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var startWidth = 0f
    private var startHeight = 0f
    private var moved = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x55000000)

        val left = config.boxLeft * width
        val top = config.boxTop * height
        val right = (config.boxLeft + config.boxWidth).coerceAtMost(1f) * width
        val bottom = (config.boxTop + config.boxHeight).coerceAtMost(1f) * height

        stroke.color = Color.YELLOW
        stroke.strokeWidth = 6f
        canvas.drawRoundRect(left, top, right, bottom, 18f, 18f, stroke)

        val gap = (bottom - top) / config.lineCount.coerceAtLeast(1)

        for (index in 0 until config.lineCount) {
            val shape = config.lines[index]
            val centerY = top + gap * index
            val lineHeight = gap * 0.72f * shape.heightFraction
            val lineWidth = (right - left) * shape.widthFraction
            val x = left + ((right - left) - lineWidth) / 2f
            val y = centerY + (gap - lineHeight) / 2f

            stroke.strokeWidth = if (index == selectedLine) 7f else 3f
            stroke.color = if (index == selectedLine || shape.unlocked) Color.YELLOW else Color.WHITE

            val radius = 10f + shape.cornerFraction * 36f
            canvas.drawRoundRect(x, y, x + lineWidth, y + lineHeight, radius, radius, stroke)
            canvas.drawText((index + 1).toString(), left + 8f, y + lineHeight / 2f + 6f, textPaint)
        }

        drawControls(canvas)
    }

    private fun drawControls(canvas: Canvas) {
        val panelTop = height - 154f
        fill.color = Color.WHITE
        canvas.drawRect(0f, panelTop, width.toFloat(), height.toFloat(), fill)

        textPaint.textSize = 15f
        canvas.drawText("LINES " + config.lineCount, 12f, panelTop + 23f, textPaint)
        canvas.drawText("SPEED " + config.scrollSpeed, 116f, panelTop + 23f, textPaint)
        canvas.drawText("LINE " + (selectedLine + 1), 220f, panelTop + 23f, textPaint)

        button(canvas, 8f, panelTop + 38f, 52f, panelTop + 82f, "-")
        button(canvas, 58f, panelTop + 38f, 102f, panelTop + 82f, "+")
        for (speed in 0..9) {
            val left = 108f + speed * 34f
            button(canvas, left, panelTop + 38f, left + 30f, panelTop + 82f, speed.toString())
        }
        button(canvas, width.toFloat() - 92f, panelTop + 38f, width.toFloat() - 8f, panelTop + 82f, "LOCK")

        button(canvas, 8f, panelTop + 92f, 68f, panelTop + 136f, "W-")
        button(canvas, 74f, panelTop + 92f, 134f, panelTop + 136f, "W+")
        button(canvas, 140f, panelTop + 92f, 200f, panelTop + 136f, "H-")
        button(canvas, 206f, panelTop + 92f, 266f, panelTop + 136f, "H+")
        button(canvas, 272f, panelTop + 92f, 334f, panelTop + 136f, "CORNER")
        button(canvas, 340f, panelTop + 92f, 418f, panelTop + 136f, "SAVE")
        button(canvas, 424f, panelTop + 92f, width.toFloat() - 8f, panelTop + 136f, "CLOSE")
    }

    private fun button(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, label: String) {
        stroke.color = Color.BLACK
        stroke.strokeWidth = 2f
        canvas.drawRect(left, top, right, bottom, stroke)
        textPaint.textSize = 11f
        val textWidth = textPaint.measureText(label)
        canvas.drawText(label, left + (right - left - textWidth) / 2f, top + (bottom - top) / 2f + 4f, textPaint)
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
                if (abs(event.x - downX) > 8f || abs(event.y - downY) > 8f) moved = true

                when (touchMode) {
                    MODE_MOVE -> {
                        config.boxLeft = (startLeft + (event.x - downX) / width).coerceIn(0f, 1f - config.boxWidth)
                        config.boxTop = (startTop + (event.y - downY) / height).coerceIn(0f, 1f - config.boxHeight)
                    }
                    MODE_RESIZE_WIDTH -> {
                        config.boxWidth = (startWidth + (event.x - downX) / width).coerceIn(0.25f, 0.95f)
                        config.boxLeft = config.boxLeft.coerceAtMost(1f - config.boxWidth)
                    }
                    MODE_RESIZE_HEIGHT -> {
                        config.boxHeight = (startHeight + (event.y - downY) / height).coerceIn(0.18f, 0.85f)
                        config.boxTop = config.boxTop.coerceAtMost(1f - config.boxHeight)
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
        if (y > height - 170f) return MODE_CONTROLS

        val left = config.boxLeft * width
        val top = config.boxTop * height
        val right = (config.boxLeft + config.boxWidth) * width
        val bottom = (config.boxTop + config.boxHeight) * height

        return when {
            x in (right - 40f)..(right + 40f) && y in top..bottom -> MODE_RESIZE_WIDTH
            y in (bottom - 40f)..(bottom + 40f) && x in left..right -> MODE_RESIZE_HEIGHT
            x in left..right && y in top..bottom -> MODE_MOVE
            else -> MODE_CONTROLS
        }
    }

    private fun selectLine(y: Float) {
        val top = config.boxTop * height
        val gap = config.boxHeight * height / config.lineCount.coerceAtLeast(1)
        selectedLine = ((y - top) / gap).toInt().coerceIn(0, config.lineCount - 1)
        invalidate()
    }

    private fun handleControl(x: Float, y: Float) {
        val top = height - 154f
        if (y < top + 34f) return

        if (y < top + 88f) {
            when {
                x <= 52f -> setCount(config.lineCount - 1)
                x <= 102f -> setCount(config.lineCount + 1)
                x in 108f..448f -> {
                    config.scrollSpeed = ((x - 108f) / 34f).toInt().coerceIn(0, 9)
                    invalidate()
                }
                x >= width - 110f -> {
                    config.lines[selectedLine].unlocked = !config.lines[selectedLine].unlocked
                    invalidate()
                }
            }
            return
        }

        when {
            x <= 68f -> editSelected(-0.08f, 0f)
            x <= 134f -> editSelected(0.08f, 0f)
            x <= 200f -> editSelected(0f, -0.08f)
            x <= 266f -> editSelected(0f, 0.08f)
            x <= 334f -> {
                val current = config.lines[selectedLine].cornerFraction
                config.lines[selectedLine].cornerFraction = if (current > 0.5f) 0f else 1f
                invalidate()
            }
            x <= 418f -> onSave(config.copy(lines = config.lines.map { it.copy() }.toMutableList()))
            else -> onClose()
        }
    }

    private fun setCount(value: Int) {
        config.lineCount = value.coerceIn(1, 50)
        while (config.lines.size < config.lineCount) config.lines += LineShape()
        while (config.lines.size > config.lineCount) config.lines.removeAt(config.lines.lastIndex)
        selectedLine = selectedLine.coerceIn(0, config.lineCount - 1)
        invalidate()
    }

    private fun editSelected(widthDelta: Float, heightDelta: Float) {
        val shape = config.lines[selectedLine]
        if (!shape.unlocked) return
        shape.widthFraction = (shape.widthFraction + widthDelta).coerceIn(0.4f, 1f)
        shape.heightFraction = (shape.heightFraction + heightDelta).coerceIn(0.35f, 1.4f)
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
