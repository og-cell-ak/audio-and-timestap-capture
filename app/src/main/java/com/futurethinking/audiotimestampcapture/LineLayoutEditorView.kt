package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class LineLayoutEditorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layout = LineLayout(
        0.08f, 0.18f, 0.92f, 0.62f, 5, 0,
        List(5) { LineShape() }
    )
    private var selectedLine = 0
    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f
    private val density = resources.displayMetrics.density

    var listener: ((LineLayout) -> Unit)? = null

    fun setLayout(value: LineLayout) {
        layout = value.normalized()
        selectedLine = selectedLine.coerceAtMost(layout.lineCount - 1)
        invalidate()
    }

    fun currentLayout(): LineLayout = layout.normalized()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xEFFFFFFF.toInt())

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.textSize = 17f * density
        canvas.drawText("TIMESTAMP GENIUS • SET LINES", 18f * density, 28f * density, paint)

        paint.textSize = 12f * density
        canvas.drawText(
            "Tap a row to select. Drag inside to move. Drag the corner to resize.",
            18f * density, 49f * density, paint
        )

        val box = boxRect()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * density
        paint.color = Color.YELLOW
        canvas.drawRect(box, paint)

        val rowHeight = box.height() / layout.lineCount.coerceAtLeast(1)
        for (i in 0 until layout.lineCount) {
            val shape = layout.shapes[i]
            val inset = (box.width() * (1f - shape.width) * 0.5f).coerceAtLeast(0f)
            val top = box.top + rowHeight * i + rowHeight * 0.10f
            val bottom = box.top + rowHeight * (i + 1) - rowHeight * 0.10f
            paint.strokeWidth = if (i == selectedLine) 4f * density else 2f * density
            canvas.drawRoundRect(
                RectF(box.left + inset, top, box.right - inset, bottom),
                shape.radius * density,
                shape.radius * density,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.textSize = 12f * density
        canvas.drawText(
            "LINES: " + layout.lineCount + "      SELECTED: " + (selectedLine + 1),
            18f * density, 76f * density, paint
        )
        canvas.drawText("−", 24f * density, 102f * density, paint)
        canvas.drawText("+", 82f * density, 102f * density, paint)

        val shape = layout.shapes[selectedLine]
        canvas.drawText(
            "Line " + (selectedLine + 1) + ": " + if (shape.unlocked) "UNLOCKED" else "LOCKED",
            130f * density, 102f * density, paint
        )

        val row1Top = height - 150f * density
        val row2Top = height - 82f * density
        drawControlRow(canvas, row1Top, listOf("UNLOCK", "W−", "W+", "H−", "H+"))
        drawControlRow(canvas, row2Top, listOf("R−", "R+", "SPEED−", "SPEED+", "SAVE"))

        paint.textSize = 12f * density
        paint.color = Color.BLACK
        canvas.drawText(
            "Speed " + layout.scrollSpeed + "/9 • Individual changes require UNLOCK.",
            18f * density, row1Top - 12f * density, paint
        )
    }

    private fun drawControlRow(canvas: Canvas, top: Float, labels: List<String>) {
        val slot = width.toFloat() / 5f
        labels.forEachIndexed { index, label ->
            val left = slot * index + 3f * density
            val right = slot * (index + 1) - 3f * density
            val bottom = top + 56f * density

            paint.style = Paint.Style.FILL
            paint.color = Color.YELLOW
            canvas.drawRoundRect(
                RectF(left, top, right, bottom),
                8f * density,
                8f * density,
                paint
            )

            paint.color = Color.BLACK
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 11f * density
            canvas.drawText(label, (left + right) / 2f, top + 34f * density, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y

                if (saveRect().contains(x, y)) {
                    listener?.invoke(layout.normalized())
                    return true
                }

                if (y in (84f * density)..(116f * density) && x < 120f * density) {
                    when {
                        x < 50f * density -> changeCount(-1)
                        x > 68f * density -> changeCount(1)
                    }
                    return true
                }

                if (y >= height - 150f * density) {
                    applyControl(x, y)
                    return true
                }

                val box = boxRect()
                if (box.contains(x, y)) {
                    val rowHeight = box.height() / layout.lineCount
                    selectedLine = ((y - box.top) / rowHeight)
                        .toInt()
                        .coerceIn(0, layout.lineCount - 1)
                    dragMode = if (
                        abs(x - box.right) < 52f * density &&
                        abs(y - box.bottom) < 52f * density
                    ) 2 else 1
                    lastX = x
                    lastY = y
                    invalidate()
                    return true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 1) {
                    val dx = (x - lastX) / width.toFloat()
                    val dy = (y - lastY) / height.toFloat()
                    val boxWidth = layout.right - layout.left
                    val boxHeight = layout.bottom - layout.top
                    val newLeft = (layout.left + dx).coerceIn(0f, 1f - boxWidth)
                    val newTop = (layout.top + dy).coerceIn(0f, 1f - boxHeight)

                    layout = LineLayout(
                        newLeft, newTop,
                        newLeft + boxWidth, newTop + boxHeight,
                        layout.lineCount, layout.scrollSpeed, layout.shapes
                    ).normalized()
                } else if (dragMode == 2) {
                    layout = LineLayout(
                        layout.left,
                        layout.top,
                        (layout.right + (x - lastX) / width.toFloat())
                            .coerceIn(layout.left + 0.15f, 1f),
                        (layout.bottom + (y - lastY) / height.toFloat())
                            .coerceIn(layout.top + 0.15f, 1f),
                        layout.lineCount,
                        layout.scrollSpeed,
                        layout.shapes
                    ).normalized()
                }

                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
                return true
            }
        }
        return true
    }

    private fun changeCount(delta: Int) {
        val count = (layout.lineCount + delta).coerceIn(1, 30)
        val shapes = List(count) { index ->
            layout.shapes.getOrNull(index)?.copy() ?: LineShape()
        }
        layout = LineLayout(
            layout.left, layout.top, layout.right, layout.bottom,
            count, layout.scrollSpeed, shapes
        ).normalized()
        selectedLine = selectedLine.coerceAtMost(count - 1)
        invalidate()
    }

    private fun applyControl(x: Float, y: Float) {
        val secondRow = y >= height - 82f * density
        val column = ((x / width.toFloat()) * 5f)
            .toInt()
            .coerceIn(0, 4)

        if (!secondRow) {
            val shape = layout.shapes[selectedLine]
            when (column) {
                0 -> shape.unlocked = !shape.unlocked
                1 -> if (shape.unlocked) shape.width = (shape.width - 0.05f).coerceAtLeast(0.35f)
                2 -> if (shape.unlocked) shape.width = (shape.width + 0.05f).coerceAtMost(1.15f)
                3 -> if (shape.unlocked) shape.height = (shape.height - 0.10f).coerceAtLeast(0.35f)
                4 -> if (shape.unlocked) shape.height = (shape.height + 0.10f).coerceAtMost(2f)
            }
        } else {
            val shape = layout.shapes[selectedLine]
            when (column) {
                0 -> if (shape.unlocked) shape.radius = (shape.radius - 4f).coerceAtLeast(0f)
                1 -> if (shape.unlocked) shape.radius = (shape.radius + 4f).coerceAtMost(40f)
                2 -> layout = layout.copy(scrollSpeed = (layout.scrollSpeed - 1).coerceAtLeast(0))
                3 -> layout = layout.copy(scrollSpeed = (layout.scrollSpeed + 1).coerceAtMost(9))
                4 -> listener?.invoke(layout.normalized())
            }
        }
        invalidate()
    }

    private fun boxRect(): RectF = RectF(
        layout.left * width,
        layout.top * height,
        layout.right * width,
        layout.bottom * height
    )

    private fun saveRect(): RectF = RectF(
        width - width / 5f,
        height - 82f * density,
        width.toFloat(),
        height.toFloat()
    )
}
