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
    private var lastX = 0f
    private var lastY = 0f
    private var mode = 0
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

    fun setExistingLayout(layout: LineLayout?, width: Int, height: Int) {
        if (layout == null || width <= 0 || height <= 0) return
        rect.set(layout.left * width, layout.top * height, layout.right * width, layout.bottom * height)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = event.x; sy = event.y; lastX = sx; lastY = sy; drawing = true
                mode = if (rect.width() > 40f && rect.height() > 40f) hitMode(sx, sy) else 0
                if (mode == 0) { rect.set(sx, sy, sx, sy); mode = 1 }
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    val x=event.x; val y=event.y
                    when(mode) {
                        1 -> rect.set(min(sx,x),min(sy,y),max(sx,x),max(sy,y))
                        2 -> { rect.offset(x-lastX,y-lastY); rect.offset(if(rect.left<0) -rect.left else if(rect.right>width) width-rect.right else 0f, if(rect.top<0) -rect.top else if(rect.bottom>height) height-rect.bottom else 0f) }
                        3 -> rect.left=min(x,rect.right-40f).coerceAtLeast(0f)
                        4 -> rect.right=max(x,rect.left+40f).coerceAtMost(width.toFloat())
                        5 -> rect.top=min(y,rect.bottom-40f).coerceAtLeast(0f)
                        6 -> rect.bottom=max(y,rect.top+40f).coerceAtMost(height.toFloat())
                        7 -> { rect.left=min(x,rect.right-40f).coerceAtLeast(0f); rect.top=min(y,rect.bottom-40f).coerceAtLeast(0f) }
                        8 -> { rect.right=max(x,rect.left+40f).coerceAtMost(width.toFloat()); rect.top=min(y,rect.bottom-40f).coerceAtLeast(0f) }
                        9 -> { rect.left=min(x,rect.right-40f).coerceAtLeast(0f); rect.bottom=max(y,rect.top+40f).coerceAtMost(height.toFloat()) }
                        10 -> { rect.right=max(x,rect.left+40f).coerceAtMost(width.toFloat()); rect.bottom=max(y,rect.top+40f).coerceAtMost(height.toFloat()) }
                    }
                    lastX=x; lastY=y; invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                mode = 0
                if (rect.width() < 40f || rect.height() < 40f) rect.setEmpty()
                invalidate(); return true
            }
        }
        return true
    }

    private fun hitMode(x: Float, y: Float): Int {
        val d=45f; val l=x<=rect.left+d; val r=x>=rect.right-d; val t=y<=rect.top+d; val b=y>=rect.bottom-d
        return when { l&&t->7; r&&t->8; l&&b->9; r&&b->10; l->3; r->4; t->5; b->6; rect.contains(x,y)->2; else->0 }
    }

    fun toLayout(w: Int, h: Int): LineLayout? {
        if (rect.width() < 40f || rect.height() < 40f) return null
        return LineLayout(rect.left/w, rect.top/h, rect.right/w, rect.bottom/h, lineCount).normalized()
    }
}
