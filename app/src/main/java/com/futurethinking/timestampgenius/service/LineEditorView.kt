package com.futurethinking.timestampgenius.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.futurethinking.timestampgenius.LayoutConfig
import com.futurethinking.timestampgenius.LineShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    private var activeControl = CONTROL_NONE
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var startWidth = 0f
    private var startHeight = 0f
    private var startLineX = 0f
    private var startLineY = 0f
    private var moved = false

    private val controls = ArrayList<Pair<RectF, Int>>()
    private var speedRect = RectF()

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
        canvas.drawColor(0x66000000)

        val box = boxRect()
        stroke.color = Color.YELLOW
        stroke.strokeWidth = dp(3f)
        canvas.drawRoundRect(box, dp(10f), dp(10f), stroke)

        for (index in 0 until config.lineCount) {
            val line = lineRect(index)
            val shape = config.lines[index]

            stroke.strokeWidth = when {
                index == selectedLine -> dp(3.5f)
                shape.unlocked -> dp(2.5f)
                else -> dp(2f)
            }
            stroke.color = if (shape.unlocked) Color.YELLOW else Color.WHITE

            val radius = dp(5f + shape.cornerFraction * 18f)
            canvas.drawRoundRect(line, radius, radius, stroke)

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(12f)
            canvas.drawText(
                (index + 1).toString(),
                box.left + dp(8f),
                line.centerY() + dp(5f),
                textPaint
            )

            if (index == selectedLine && shape.unlocked) {
                fill.color = Color.YELLOW
                canvas.drawCircle(line.right, line.centerY(), dp(5f), fill)
                canvas.drawCircle(line.centerX(), line.bottom, dp(5f), fill)
            }
        }

        drawControls(canvas)
    }

    private fun drawControls(canvas: Canvas) {
        controls.clear()

        val horizontalGap = dp(6f)
        val margin = dp(8f)
        val columnWidth = min(
            dp(96f),
            (width - margin * 2f - horizontalGap * 3f).coerceAtLeast(dp(64f)) / 4f
        )

        val targetRowHeight = min(
            dp(52f),
            max(dp(40f), columnWidth * 0.55f)
        )

        val maxPanelHeight = height * 0.65f
        val fixed = dp(28f) + dp(10f) + horizontalGap * 5f
        var rowHeight = targetRowHeight
        if (fixed + rowHeight * 5f > maxPanelHeight) {
            rowHeight = ((maxPanelHeight - fixed) / 5f)
                .coerceAtLeast(dp(30f))
        }

        val panelHeight = fixed + rowHeight * 5f
        val panelTop = height - panelHeight

        fill.color = Color.WHITE
        canvas.drawRect(0f, panelTop, width.toFloat(), height.toFloat(), fill)

        textPaint.color = Color.BLACK
        textPaint.textSize = min(dp(13f), rowHeight * 0.28f)
        canvas.drawText(
            "LAYOUT • LINE ${selectedLine + 1}/${config.lineCount}" +
                " • " + if (config.lines[selectedLine].unlocked) "UNLOCKED" else "LOCKED",
            margin,
            panelTop + dp(18f),
            textPaint
        )

        speedRect = RectF(
            margin,
            panelTop + dp(24f),
            width - margin,
            panelTop + dp(49f)
        )
        drawSpeedSlider(canvas)

        val gridWidth = columnWidth * 4f + horizontalGap * 3f
        val startX = (width - gridWidth) / 2f

        val actions = arrayOf(
            arrayOf(CONTROL_LINES_MINUS, CONTROL_LINES_PLUS, CONTROL_LINE_MINUS, CONTROL_LINE_PLUS),
            arrayOf(CONTROL_LOCK, CONTROL_LEFT, CONTROL_RIGHT, CONTROL_UP),
            arrayOf(CONTROL_DOWN, CONTROL_WIDTH_MINUS, CONTROL_WIDTH_PLUS, CONTROL_HEIGHT_MINUS),
            arrayOf(CONTROL_HEIGHT_PLUS, CONTROL_CORNER, CONTROL_RESET, CONTROL_SAVE)
        )

        val firstRowTop = panelTop + dp(56f)
        for (row in actions.indices) {
            val top = firstRowTop + row * (rowHeight + horizontalGap)
            for (col in 0 until 4) {
                val left = startX + col * (columnWidth + horizontalGap)
                val action = actions[row][col]
                button(canvas, left, top, columnWidth, rowHeight, controlLabel(action), action)
            }
        }

        val closeTop = firstRowTop + 4 * (rowHeight + horizontalGap)
        button(canvas, startX, closeTop, gridWidth, rowHeight, "CLOSE", CONTROL_CLOSE)
    }

    private fun drawSpeedSlider(canvas: Canvas) {
        val left = speedRect.left + dp(58f)
        val right = speedRect.right
        val y = speedRect.centerY()

        textPaint.color = Color.BLACK
        textPaint.textSize = min(dp(11f), speedRect.height() * 0.42f)
        canvas.drawText(
            "SPEED ${config.scrollSpeed}",
            speedRect.left,
            y + textPaint.textSize * 0.35f,
            textPaint
        )

        stroke.color = Color.BLACK
        stroke.strokeWidth = dp(2f)
        canvas.drawLine(left, y, right, y, stroke)

        val step = (right - left) / 9f
        for (i in 0..9) {
            val x = left + step * i
            fill.color = if (i == config.scrollSpeed) Color.BLACK else Color.WHITE
            canvas.drawCircle(x, y, dp(6f), fill)
            stroke.color = Color.BLACK
            stroke.strokeWidth = dp(1.5f)
            canvas.drawCircle(x, y, dp(6f), stroke)
        }
    }

    private fun controlLabel(action: Int): String {
        return when (action) {
            CONTROL_LINES_MINUS -> "LINES −"
            CONTROL_LINES_PLUS -> "LINES +"
            CONTROL_LINE_MINUS -> "LINE −"
            CONTROL_LINE_PLUS -> "LINE +"
            CONTROL_LOCK -> if (config.lines[selectedLine].unlocked) "LOCK" else "UNLOCK"
            CONTROL_LEFT -> "←"
            CONTROL_RIGHT -> "→"
            CONTROL_UP -> "↑"
            CONTROL_DOWN -> "↓"
            CONTROL_WIDTH_MINUS -> "W −"
            CONTROL_WIDTH_PLUS -> "W +"
            CONTROL_HEIGHT_MINUS -> "H −"
            CONTROL_HEIGHT_PLUS -> "H +"
            CONTROL_CORNER -> "CORNER"
            CONTROL_RESET -> "RESET"
            CONTROL_SAVE -> "SAVE"
            else -> ""
        }
    }

    private fun button(
        canvas: Canvas,
        left: Float,
        top: Float,
        buttonWidth: Float,
        buttonHeight: Float,
        label: String,
        action: Int
    ) {
        val rect = RectF(left, top, left + buttonWidth, top + buttonHeight)
        controls += rect to action

        fill.color = if (action == CONTROL_SAVE) Color.BLACK else Color.WHITE
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)

        stroke.color = Color.BLACK
        stroke.strokeWidth = dp(1.5f)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), stroke)

        textPaint.color = if (action == CONTROL_SAVE) Color.WHITE else Color.BLACK
        textPaint.textSize = min(dp(12f), buttonHeight * 0.30f)
        val textWidth = textPaint.measureText(label)
        canvas.drawText(
            label,
            rect.centerX() - textWidth / 2f,
            rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint
        )
    }

    private fun boxRect(): RectF {
        val left = config.boxLeft * width
        val top = config.boxTop * height
        val right = (config.boxLeft + config.boxWidth).coerceAtMost(1f) * width
        val bottom = (config.boxTop + config.boxHeight).coerceAtMost(1f) * height
        return RectF(left, top, right, bottom)
    }

    private fun lineRect(index: Int): RectF {
        val box = boxRect()
        val gap = box.height() / config.lineCount.coerceAtLeast(1)
        val shape = config.lines[index]

        val lineWidth = box.width() * shape.widthFraction.coerceIn(.4f, 1f)
        val lineHeight = (gap * 0.72f * shape.heightFraction)
            .coerceIn(dp(10f), gap * 1.5f)
        val centerX = box.centerX() +
            shape.xOffsetFraction.coerceIn(-.45f, .45f) * box.width()
        val centerY = box.top +
            gap * (index + 0.5f) +
            shape.yOffsetFraction.coerceIn(-.45f, .45f) * gap

        val lineLeft = max(box.left, centerX - lineWidth / 2f)
        val lineRight = min(box.right, centerX + lineWidth / 2f)
        val lineTop = max(box.top, centerY - lineHeight / 2f)
        val lineBottom = min(box.bottom, centerY + lineHeight / 2f)

        return RectF(
            lineLeft,
            lineTop,
            max(lineRight, lineLeft + 1f),
            max(lineBottom, lineTop + 1f)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                activeControl = CONTROL_NONE
                touchMode = hitTest(event.x, event.y)

                if (touchMode == MODE_BOX_MOVE ||
                    touchMode == MODE_BOX_WIDTH ||
                    touchMode == MODE_BOX_HEIGHT
                ) {
                    startLeft = config.boxLeft
                    startTop = config.boxTop
                    startWidth = config.boxWidth
                    startHeight = config.boxHeight
                }

                if (touchMode == MODE_LINE_MOVE) {
                    val shape = config.lines[selectedLine]
                    startLineX = shape.xOffsetFraction
                    startLineY = shape.yOffsetFraction
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > dp(8f) || abs(event.y - downY) > dp(8f)) {
                    moved = true
                }

                when (touchMode) {
                    MODE_BOX_MOVE -> {
                        config.boxLeft = (
                            startLeft + (event.x - downX) / width
                        ).coerceIn(0f, 1f - config.boxWidth)
                        config.boxTop = (
                            startTop + (event.y - downY) / height
                        ).coerceIn(0f, 1f - config.boxHeight)
                    }

                    MODE_BOX_WIDTH -> {
                        config.boxWidth = (
                            startWidth + (event.x - downX) / width
                        ).coerceIn(.25f, .95f)
                        config.boxLeft = config.boxLeft.coerceAtMost(1f - config.boxWidth)
                    }

                    MODE_BOX_HEIGHT -> {
                        config.boxHeight = (
                            startHeight + (event.y - downY) / height
                        ).coerceIn(.18f, .85f)
                        config.boxTop = config.boxTop.coerceAtMost(1f - config.boxHeight)
                    }

                    MODE_LINE_MOVE -> {
                        val shape = config.lines[selectedLine]
                        val box = boxRect()
                        val gap = box.height() / config.lineCount.coerceAtLeast(1)
                        shape.xOffsetFraction = (
                            startLineX + (event.x - downX) / box.width()
                        ).coerceIn(-.45f, .45f)
                        shape.yOffsetFraction = (
                            startLineY + (event.y - downY) / gap
                        ).coerceIn(-.45f, .45f)
                    }

                    MODE_SPEED -> setSpeedFromX(event.x)
                }

                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (touchMode == MODE_CONTROLS && !moved) {
                    activeControl = controls
                        .firstOrNull { it.first.contains(event.x, event.y) }
                        ?.second ?: CONTROL_NONE

                    if (activeControl != CONTROL_NONE) {
                        handleControl(activeControl)
                    } else if (speedRect.contains(event.x, event.y)) {
                        setSpeedFromX(event.x)
                    }
                } else if (touchMode == MODE_SELECT_LINE && !moved) {
                    selectLineAt(event.x, event.y)
                }

                return true
            }
        }

        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        if (speedRect.contains(x, y)) return MODE_SPEED
        if (y >= height - currentPanelHeight()) return MODE_CONTROLS

        val box = boxRect()

        for (index in 0 until config.lineCount) {
            if (lineRect(index).contains(x, y)) {
                selectedLine = index
                return if (config.lines[index].unlocked) MODE_LINE_MOVE else MODE_SELECT_LINE
            }
        }

        return when {
            x in (box.right - dp(32f))..(box.right + dp(32f)) &&
                y in box.top..box.bottom -> MODE_BOX_WIDTH

            y in (box.bottom - dp(32f))..(box.bottom + dp(32f)) &&
                x in box.left..box.right -> MODE_BOX_HEIGHT

            x in box.left..box.right && y in box.top..box.bottom -> MODE_BOX_MOVE
            else -> MODE_CONTROLS
        }
    }

    private fun currentPanelHeight(): Float {
        val horizontalGap = dp(6f)
        val margin = dp(8f)
        val columnWidth = min(
            dp(96f),
            (width - margin * 2f - horizontalGap * 3f).coerceAtLeast(dp(64f)) / 4f
        )
        val targetRowHeight = min(
            dp(52f),
            max(dp(40f), columnWidth * 0.55f)
        )
        val maxPanelHeight = height * 0.65f
        val fixed = dp(28f) + dp(10f) + horizontalGap * 5f
        val rowHeight = if (fixed + targetRowHeight * 5f > maxPanelHeight) {
            ((maxPanelHeight - fixed) / 5f).coerceAtLeast(dp(30f))
        } else {
            targetRowHeight
        }
        return fixed + rowHeight * 5f
    }

    private fun selectLineAt(x: Float, y: Float) {
        val index = (0 until config.lineCount)
            .firstOrNull { lineRect(it).contains(x, y) }

        if (index != null) {
            selectedLine = index
            invalidate()
        }
    }

    private fun handleControl(action: Int) {
        when (action) {
            CONTROL_LINES_MINUS -> setCount(config.lineCount - 1)
            CONTROL_LINES_PLUS -> setCount(config.lineCount + 1)
            CONTROL_LINE_MINUS -> selectedLine =
                (selectedLine - 1).coerceIn(0, config.lineCount - 1)
            CONTROL_LINE_PLUS -> selectedLine =
                (selectedLine + 1).coerceIn(0, config.lineCount - 1)

            CONTROL_LOCK -> {
                config.lines[selectedLine].unlocked =
                    !config.lines[selectedLine].unlocked
            }

            CONTROL_LEFT -> nudge(-0.04f, 0f)
            CONTROL_RIGHT -> nudge(0.04f, 0f)
            CONTROL_UP -> nudge(0f, -0.04f)
            CONTROL_DOWN -> nudge(0f, 0.04f)
            CONTROL_WIDTH_MINUS -> editSize(-0.08f, 0f)
            CONTROL_WIDTH_PLUS -> editSize(0.08f, 0f)
            CONTROL_HEIGHT_MINUS -> editSize(0f, -0.08f)
            CONTROL_HEIGHT_PLUS -> editSize(0f, 0.08f)

            CONTROL_CORNER -> {
                val shape = config.lines[selectedLine]
                shape.cornerFraction =
                    if (shape.cornerFraction > .5f) 0f else 1f
            }

            CONTROL_RESET -> {
                config = LayoutConfig()
                selectedLine = 0
            }

            CONTROL_SAVE -> onSave(
                config.copy(
                    lines = config.lines.map { it.copy() }.toMutableList()
                )
            )

            CONTROL_CLOSE -> onClose()
        }

        invalidate()
    }

    private fun setSpeedFromX(x: Float) {
        if (speedRect.width() <= 0f) return

        config.scrollSpeed = (
            ((x - speedRect.left) / speedRect.width()) * 9f
        ).roundToInt().coerceIn(0, 9)

        invalidate()
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

    private fun nudge(dx: Float, dy: Float) {
        val shape = config.lines[selectedLine]
        if (!shape.unlocked) return

        shape.xOffsetFraction =
            (shape.xOffsetFraction + dx).coerceIn(-.45f, .45f)
        shape.yOffsetFraction =
            (shape.yOffsetFraction + dy).coerceIn(-.45f, .45f)
    }

    private fun editSize(widthDelta: Float, heightDelta: Float) {
        val shape = config.lines[selectedLine]
        if (!shape.unlocked) return

        shape.widthFraction =
            (shape.widthFraction + widthDelta).coerceIn(.4f, 1f)
        shape.heightFraction =
            (shape.heightFraction + heightDelta).coerceIn(.35f, 1.4f)
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_BOX_MOVE = 1
        private const val MODE_BOX_WIDTH = 2
        private const val MODE_BOX_HEIGHT = 3
        private const val MODE_LINE_MOVE = 4
        private const val MODE_SELECT_LINE = 5
        private const val MODE_SPEED = 6
        private const val MODE_CONTROLS = 10

        private const val CONTROL_NONE = 0
        private const val CONTROL_LINES_MINUS = 1
        private const val CONTROL_LINES_PLUS = 2
        private const val CONTROL_LINE_MINUS = 3
        private const val CONTROL_LINE_PLUS = 4
        private const val CONTROL_LOCK = 5
        private const val CONTROL_LEFT = 6
        private const val CONTROL_RIGHT = 7
        private const val CONTROL_UP = 8
        private const val CONTROL_DOWN = 9
        private const val CONTROL_WIDTH_MINUS = 10
        private const val CONTROL_WIDTH_PLUS = 11
        private const val CONTROL_HEIGHT_MINUS = 12
        private const val CONTROL_HEIGHT_PLUS = 13
        private const val CONTROL_CORNER = 14
        private const val CONTROL_RESET = 15
        private const val CONTROL_SAVE = 16
        private const val CONTROL_CLOSE = 17
    }
}
