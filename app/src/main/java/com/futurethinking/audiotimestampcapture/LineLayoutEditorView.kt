package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class LineLayoutEditorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sx = 0f
    private var sy = 0f
    private var drawing = false
    var rect = RectF()
        private set
    var lineCount = 5
        set(value) { field = value.coerceIn(1, 30); invalidate() }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0xFFFFFF00.toInt()
        if (rect.width() > 4f && rect.height() > 4f) {
            canvas.drawRect(rect, paint)
            paint.strokeWidth = 2f
            paint.color = 0xFFFFC107.toInt()
            val gap = rect.height() / lineCount
            for (i in 1 until lineCount) {
                val y = rect.top + gap * i
                canvas.drawLine(rect.left, y, rect.right, y, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x; sy = event.y; drawing = true
                rect.set(sx, sy, sx, sy); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    rect.set(min(sx,event.x), min(sy,event.y), max(sx,event.x), max(sy,event.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                if (rect.width() < 40f || rect.height() < 40f) rect.setEmpty()
                invalidate(); return true
            }
        }
        return true
    }

    fun toLayout(w: Int, h: Int): LineLayout? {
        if (rect.width() < 40f || rect.height() < 40f) return null
        return LineLayout(rect.left/w, rect.top/h, rect.right/w, rect.bottom/h, lineCount).normalized()
    }
}
