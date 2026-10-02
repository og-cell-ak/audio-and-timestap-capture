package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.math.max

class ScreenOcrCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val layoutProvider: () -> LineLayout?,
    private val startElapsed: Long,
    private val onLines: (List<ScreenLine>) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val handler=Handler(Looper.getMainLooper())
    private val latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val hindi=TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private var reader:ImageReader?=null
    private var display:VirtualDisplay?=null
    @Volatile private var stopped=true
    @Volatile private var busy=false
    private var lastFrame=0L

    fun start() {
        stopped=false
        val dm=context.resources.displayMetrics
        val w=dm.widthPixels
        val h=dm.heightPixels
        reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
        reader!!.setOnImageAvailableListener({ r ->
            if(stopped || busy) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val now=SystemClock.elapsedRealtime()
            if(now-lastFrame<500L) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val layout=layoutProvider()
            if(layout==null){r.acquireLatestImage()?.close();return@setOnImageAvailableListener}
            val image=r.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrame=now
            busy=true
            scope.launch {
                try {
                    val bmp=withContext(Dispatchers.Default){
                        val plane=image.planes[0]
                        val paddedWidth=image.width+((plane.rowStride-plane.pixelStride*image.width).coerceAtLeast(0)/plane.pixelStride)
                        val padded=Bitmap.createBitmap(paddedWidth,image.height,Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val full=if(paddedWidth==image.width) padded else Bitmap.createBitmap(padded,0,0,image.width,image.height).also{padded.recycle()}
                        full
                    }
                    image.close()
                    val lines=readLines(bmp,layout)
                    bmp.recycle()
                    if(!stopped) withContext(Dispatchers.Main){onLines(lines)}
                } catch(t:Throwable) {
                    runCatching{image.close()}
                    if(!stopped) withContext(Dispatchers.Main){onStatus("OCR warning • "+(t.message ?: "screen read failed"))}
                } finally { busy=false }
            }
        },handler)
        display=projection.createVirtualDisplay(
            "TimestampGeniusScreen",w,h,dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,null,handler
        )
    }

    private suspend fun readLines(full:Bitmap,layout:LineLayout):List<ScreenLine> {
        val result=mutableListOf<ScreenLine>()
        val boxTop=layout.top*full.height
        val boxHeight=(layout.bottom-layout.top).coerceAtLeast(.05f)*full.height
        val rowHeight=boxHeight/layout.lineCount.coerceAtLeast(1)
        for(i in 0 until layout.lineCount) {
            if(stopped) break
            val shape=layout.shapes.getOrNull(i) ?: LineShape()
            val cy=boxTop+rowHeight*(i+.5f)
            val ch=(rowHeight*.78f*shape.height).coerceIn(8f,rowHeight*.96f)
            val cw=(layout.right-layout.left)*full.width*shape.width
            val cx=((layout.left+layout.right)/2f)*full.width
            val l=(cx-cw/2f).toInt().coerceIn(0,full.width-1)
            val r=(cx+cw/2f).toInt().coerceIn(l+1,full.width)
            val t=(cy-ch/2f).toInt().coerceIn(0,full.height-1)
            val b=(cy+ch/2f).toInt().coerceIn(t+1,full.height)
            val crop=Bitmap.createBitmap(full,l,t,r-l,b-t)
            try {
                val a=runCatching{latin.process(InputImage.fromBitmap(crop,0)).await()}.getOrNull()
                val h=runCatching{hindi.process(InputImage.fromBitmap(crop,0)).await()}.getOrNull()
                val best=choose(a,h)
                val words=best?.textBlocks.orEmpty().flatMap{it.lines}.flatMap{it.elements}.mapNotNull{el->
                    val bb=el.boundingBox ?: return@mapNotNull null
                    WordBox(el.text,(l+bb.left).toFloat(),(t+bb.top).toFloat(),(l+bb.right).toFloat(),(t+bb.bottom).toFloat())
                }
                val text=best?.text?.replace(Regex("""\s+""")," ")?.trim().orEmpty()
                result+=ScreenLine(i+1,text,SystemClock.elapsedRealtime()-startElapsed,words)
            } finally { crop.recycle() }
        }
        return result
    }

    private fun choose(a:Text?,b:Text?):Text? {
        val at=a?.text?.trim().orEmpty()
        val bt=b?.text?.trim().orEmpty()
        return when {
            at.isBlank() && bt.isBlank()->null
            bt.length>at.length->b
            else->a
        }
    }

    fun stop() {
        stopped=true
        runCatching{display?.release()}
        runCatching{reader?.close()}
        display=null;reader=null;busy=false
        scope.cancel()
        runCatching{latin.close()}
        runCatching{hindi.close()}
    }
}
