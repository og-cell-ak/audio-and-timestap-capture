package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class LineGuideView(context: Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var layout:LineLayout?=null
        set(value){field=value?.normalized();invalidate()}
    var lineTexts:List<ScreenLine> = emptyList()
        set(value){field=value;invalidate()}
    var currentLine=0
        set(value){field=value;invalidate()}
    var currentWordProgress=0
        set(value){field=value;invalidate()}
    var recording=false
        set(value){field=value;invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c)
        val l=layout ?: return
        val box=RectF(l.left*width,l.top*height,l.right*width,l.bottom*height)
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=4f
        paint.color=Color.YELLOW
        c.drawRect(box,paint)
        val row=box.height()/l.lineCount
        for(i in 0 until l.lineCount){
            val s=l.shapes.getOrNull(i) ?: LineShape()
            val inset=(row*s.width*.06f).coerceAtLeast(0f)
            val y1=box.top+row*i+row*.12f
            val y2=box.top+row*(i+1)-row*.12f
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=if(i==currentLine) 4f else 2f
            paint.color=if(i==currentLine) Color.YELLOW else 0x88FFFF00.toInt()
            c.drawRoundRect(RectF(box.left+inset,y1,box.right-inset,y2),s.radius,s.radius,paint)
        }
        if(recording){
            paint.style=Paint.Style.FILL
            paint.color=0x22FFFF00
            c.drawRect(box,paint)
        }
        lineTexts.getOrNull(currentLine)?.wordBoxes?.take(currentWordProgress)?.forEach{
            paint.color=0x99FFFF00.toInt()
            c.drawRect(it.left,it.top,it.right,it.bottom,paint)
        }
    }
}
