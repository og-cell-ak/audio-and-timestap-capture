package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class LineGuideView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var layout: LineLayout? = null
        set(value) { field=value; invalidate() }
    var highlightLine=-1
        set(value) { field=value; invalidate() }
    var running=false
        set(value) { field=value; invalidate() }
    var highlightWord=""
        set(value) { field=value; invalidate() }
    var lineTexts: List<ScreenLine> = emptyList()
        set(value) { field=value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val l=layout ?: return
        val left=l.left*width
        val top=l.top*height
        val right=l.right*width
        val bottom=l.bottom*height
        val gap=(bottom-top)/l.lineCount.coerceAtLeast(1)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=4f
        paint.color=0xFFFFFF00.toInt()
        canvas.drawRect(left,top,right,bottom,paint)
        paint.strokeWidth=2f
        paint.color=0xFFFFC107.toInt()
        for(i in 1 until l.lineCount) canvas.drawLine(left,top+gap*i,right,top+gap*i,paint)

        if(highlightLine in 1..l.lineCount && highlightWord.isNotBlank()) {
            val text=lineTexts.firstOrNull{it.index==highlightLine}?.text ?: return
            val words=text.split(Regex("\\s+")).filter{it.isNotBlank()}
            val baseline=top+gap*(highlightLine-0.32f)
            paint.style=Paint.Style.FILL
            paint.textSize=(gap*.30f).coerceIn(16f,34f)
            var x=left+10f
            val target=norm(highlightWord)
            for(word in words) {
                val clean=norm(word)
                paint.color=if(clean==target || target.contains(clean) || clean.contains(target)) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
                canvas.drawText(word,x,baseline,paint)
                x+=paint.measureText(word)+9f
                if(x>right-10f) break
            }
        }
    }
    private fun norm(s:String)=s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"),"")
}
