package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class LineLayoutEditorView(context:Context):View(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var layout=LineLayout(.08f,.18f,.92f,.62f,5,0,List(5){LineShape()})
    private var selected=0
    private var dragMode=0
    private var lastX=0f
    private var lastY=0f
    private val d=resources.displayMetrics.density

    fun setLayout(value:LineLayout){layout=value.normalized();selected=selected.coerceAtMost(layout.lineCount-1);invalidate()}
    fun currentLayout():LineLayout=layout.normalized()

    override fun onDraw(c:Canvas){
        c.drawColor(0xCCFFFFFF.toInt())
        val box=box()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=4*d;paint.color=Color.YELLOW;c.drawRect(box,paint)
        val row=box.height()/layout.lineCount
        for(i in 0 until layout.lineCount){
            val s=layout.shapes[i]
            val inset=(box.width()*(1f-s.width)*.5f).coerceAtLeast(0f)
            val y1=box.top+row*i+row*.12f
            val y2=box.top+row*(i+1)-row*.12f
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=if(i==selected) 4*d else 2*d
            paint.color=Color.YELLOW
            c.drawRoundRect(RectF(box.left+inset,y1,box.right-inset,y2),s.radius*d,s.radius*d,paint)
        }
        paint.style=Paint.Style.FILL;paint.color=Color.BLACK
        paint.textSize=18*d;c.drawText("TIMESTAMP GENIUS • SET LINES",18*d,30*d,paint)
        paint.textSize=13*d;c.drawText("Tap a row to select it. Drag the box to move. Drag the yellow corner to resize.",18*d,52*d,paint)
        paint.textSize=12*d
        c.drawText("LINE COUNT",18*d,76*d,paint)
        c.drawText("−",18*d,102*d,paint)
        c.drawText(layout.lineCount.toString(),50*d,102*d,paint)
        c.drawText("+",86*d,102*d,paint)
        val s=layout.shapes[selected]
        c.drawText("Selected line "+(selected+1)+" "+if(s.unlocked)"UNLOCKED" else "LOCKED",140*d,102*d,paint)
        c.drawText("UNLOCK",18*d,height-104*d,paint)
        c.drawText("WIDTH −   WIDTH +",100*d,height-104*d,paint)
        c.drawText("HEIGHT −  HEIGHT +",270*d,height-104*d,paint)
        c.drawText("RADIUS −  RADIUS +",455*d,height-104*d,paint)
        c.drawText("SPEED −   "+layout.scrollSpeed+"   SPEED +",18*d,height-68*d,paint)
        paint.textSize=16*d
        paint.color=Color.YELLOW
        c.drawRect(width-150*d,height-52*d,width-12*d,height-10*d,paint)
        paint.color=Color.BLACK
        c.drawText("SAVE LAYOUT",width-142*d,height-23*d,paint)
    }

    override fun onTouchEvent(e:MotionEvent):Boolean{
        val x=e.x;val y=e.y
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{
                lastX=x;lastY=y
                if(saveRect().contains(x,y)){listener?.invoke(layout.normalized());return true}
                if(y in 84*d..118*d && x<120*d){
                    if(x<38*d) changeCount(-1) else if(x>72*d) changeCount(1)
                    return true
                }
                if(y>height-128*d){
                    applyBottom(x,y);return true
                }
                val b=box()
                if(b.contains(x,y)){
                    val row=b.height()/layout.lineCount
                    selected=((y-b.top)/row).toInt().coerceIn(0,layout.lineCount-1)
                    dragMode=if(kotlin.math.abs(x-b.right)<48*d && kotlin.math.abs(y-b.bottom)<48*d)2 else 1
                    lastX=x;lastY=y;invalidate();return true
                }
            }
            MotionEvent.ACTION_MOVE->{
                val b=box()
                if(dragMode==1){layout=LineLayout((layout.left+(x-lastX)/width).coerceIn(0f,.8f),(layout.top+(y-lastY)/height).coerceIn(0f,.8f),(layout.right+(x-lastX)/width).coerceIn(.2f,1f),(layout.bottom+(y-lastY)/height).coerceIn(.2f,1f),layout.lineCount,layout.scrollSpeed,layout.shapes).normalized()}
                else if(dragMode==2){layout=LineLayout(layout.left,layout.top,(layout.right+(x-lastX)/width).coerceIn(layout.left+.15f,1f),(layout.bottom+(y-lastY)/height).coerceIn(layout.top+.15f,1f),layout.lineCount,layout.scrollSpeed,layout.shapes).normalized()}
                lastX=x;lastY=y;invalidate();return true
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragMode=0;return true}
        }
        return true
    }

    private fun changeCount(delta:Int){
        val n=(layout.lineCount+delta).coerceIn(1,30)
        val shapes=MutableList(n){i->layout.shapes.getOrNull(i)?.copy()?:LineShape()}
        layout=LineLayout(layout.left,layout.top,layout.right,layout.bottom,n,layout.scrollSpeed,shapes)
        selected=selected.coerceAtMost(n-1);invalidate()
    }

    private fun applyBottom(x:Float,y:Float){
        val s=layout.shapes[selected]
        val w=width/d
        val slot=(x/d)
        when{
            x<85*d->s.unlocked=!s.unlocked
            slot<190->if(s.unlocked)s.width=(s.width-.05f).coerceAtLeast(.35f)
            slot<285->if(s.unlocked)s.width=(s.width+.05f).coerceAtMost(1.15f)
            slot<380->if(s.unlocked)s.height=(s.height-.1f).coerceAtLeast(.35f)
            slot<475->if(s.unlocked)s.height=(s.height+.1f).coerceAtMost(2f)
            slot<570->if(s.unlocked)s.radius=(s.radius-4f).coerceAtLeast(0f)
            slot<665->if(s.unlocked)s.radius=(s.radius+4f).coerceAtMost(40f)
            else->{ if(slot<760)layout=layout.copy(scrollSpeed=(layout.scrollSpeed-1).coerceAtLeast(0)) else layout=layout.copy(scrollSpeed=(layout.scrollSpeed+1).coerceAtMost(9)) }
        }
        invalidate()
    }

    private fun box()=RectF(layout.left*width,layout.top*height,layout.right*width,layout.bottom*height)
    private fun saveRect()=RectF(width-160*d,height-62*d,width.toFloat(),height.toFloat())
    var listener:((LineLayout)->Unit)?=null
}
