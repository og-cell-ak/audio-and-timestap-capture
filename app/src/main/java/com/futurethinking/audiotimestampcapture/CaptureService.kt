package com.futurethinking.audiotimestampcapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale

class CaptureService:Service(){
    companion object{
        const val EXTRA_RESULT_CODE="result_code"
        const val EXTRA_RESULT_DATA="result_data"
        const val ACTION_STATUS="capture_status"
        const val ACTION_PDF_READY="pdf_ready"
        const val ACTION_START="start_capture"
        const val ACTION_STOP="stop_capture"
        const val ACTION_SAVE="save_capture"
        const val ACTION_FRESH="fresh_capture"
        const val EXTRA_MESSAGE="message"
        const val EXTRA_URI="uri"

        fun stopAndClear(context:Context){
            context.stopService(Intent(context,CaptureService::class.java))
        }
    }

    private lateinit var wm:WindowManager
    private var projection:android.media.projection.MediaProjection?=null
    private var bubble:TextView?=null
    private var menu:LinearLayout?=null
    private var editor:LineLayoutEditorView?=null
    private var guide:LineGuideView?=null
    private var ocr:ScreenOcrCapture?=null
    private var speech:LiveSpeechTimer?=null
    private var layout:LineLayout?=null
    private var recording=false
    private var saving=false
    private var scrollWarningSent=false
    private var startElapsed=0L
    private val lines=mutableListOf<TimedScript>()
    private var currentLine=0
    private val accumulated=StringBuilder()
    private val scrollHandler=Handler(Looper.getMainLooper())
    private val scrollRunnable=object:Runnable{
        override fun run(){
            if(recording){
                val speed=layout?.scrollSpeed ?: 0
                if(speed>0){
                    if(!ScriptAccessibilityService.scrollForward() && !scrollWarningSent){
                        scrollWarningSent=true
                        sendStatus("AUTO SCROLL unavailable • enable Timestamp Genius accessibility service")
                    }
                    scrollHandler.postDelayed(this,(1900L-(speed*170L)).coerceIn(350L,1900L))
                }
            }
        }
    }

    override fun onCreate(){
        super.onCreate()
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        layout=SessionStore.loadLayout(this)
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_START->{startRecording();return START_STICKY}
            ACTION_STOP->{stopRecording();return START_STICKY}
            ACTION_SAVE->{savePdf();return START_NOT_STICKY}
            ACTION_FRESH->{stopRecording();lines.clear();currentLine=0;accumulated.clear();layout=SessionStore.loadLayout(this);sendStatus("NEW SESSION READY");return START_STICKY}
        }
        if(projection==null){
            val code=intent?.getIntExtra(EXTRA_RESULT_CODE,-1) ?: -1
            val data=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if(code>0 && data!=null) startProjection(code,data) else {sendStatus("Screen capture permission was not granted");stopSelf()}
        }
        return START_STICKY
    }

    private fun startProjection(code:Int,data:Intent){
        startForegroundNotification()
        projection=(getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager).getMediaProjection(code,data)
        if(projection==null){sendStatus("Unable to create screen capture session");stopSelf();return}
        projection?.registerCallback(object:android.media.projection.MediaProjection.Callback(){
            override fun onStop(){sendStatus("Screen capture stopped");savePdf()}
        },Handler(Looper.getMainLooper()))
        showBubble()
        showGuide()
        sendStatus("READY • press SET LINES before START")
    }

    private fun startForegroundNotification(){
        val n=NotificationCompat.Builder(this,"timestamp_genius")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Timestamp Genius")
            .setContentText("Device audio and screen timing")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        ServiceCompat.startForeground(this,77,n,type)
    }

    private fun startRecording(){
        if(projection==null){sendStatus("START SESSION from the main app first");return}
        if(recording)return
        val l=(layout ?: SessionStore.loadLayout(this)).normalized()
        layout=l
        if(!SessionStore.isLayoutSaved(this)){sendStatus("SET LINES and SAVE LAYOUT before START");return}
        if(l.lineCount<1){sendStatus("SET LINES must contain at least one line");return}
        stopSubcomponents()
        recording=true
        currentLine=0
        accumulated.clear()
        startElapsed=android.os.SystemClock.elapsedRealtime()
        val expected=SessionStore.loadScript(this)
        if(expected.size > l.lineCount){
            layout = LineLayout(l.left,l.top,l.right,maxOf(l.bottom,0.95f),l.lineCount,l.scrollSpeed,l.shapes).normalized()
        }
        val useHindi=expected.any{it.text.any{ch->ch in 'ऀ'..'ॿ'}}
        guide?.apply{layout=l;currentLine=0;currentWordProgress=0;recording=true}
        startAutoScroll()
        val o=ScreenOcrCapture(this,projection!!,{layout},startElapsed,{screen->
            latestScreenLines=screen
            guide?.lineTexts=screen
        },{sendStatus(it)})
        ocr=o;o.start()
        val s=LiveSpeechTimer(this,projection!!,useHindi,{_,endMs,words->
            val progress=if(words.isNotEmpty()) (words.size.coerceAtMost(guideWordCapacity())).coerceAtLeast(0) else 0
            guide?.currentWordProgress=progress
        },{segment->handleSpeech(segment,expected)},{sendStatus(it)})
        speech=s;s.start()
        sendStatus("STARTED • device audio + screen OCR")
    }

    private var latestScreenLines:List<ScreenLine> = emptyList()

    private fun handleSpeech(segment:SpokenSegment,expected:List<StoredScriptLine>){
        val expectedLine=when{
            expected.isNotEmpty()->expected.getOrNull(currentLine)
            else->latestScreenLines.firstOrNull{it.index==currentLine+1 && it.text.isNotBlank()}?.let{StoredScriptLine(it.index,it.text)}
        }
        val targetText=expectedLine?.text ?: run{
            sendStatus("Waiting for readable text in line "+(currentLine+1))
            return
        }
        if(targetText.startsWith("[unreadable")){sendStatus("Line "+(currentLine+1)+" could not be read");return}

        accumulated.append(' ').append(segment.text)
        val score=similarity(accumulated.toString(),targetText)
        val exact=progressMatched(targetText,accumulated.toString())
        if(exact>=FuzzyMatcher.tokens(targetText).size || score>=.72f){
            val row=TimedScript(segment.endMs,currentLine+1,targetText,(score*.9f+segment.confidence*.1f).coerceIn(0f,1f),true)
            lines.removeAll{it.lineNumber==currentLine+1}
            lines.add(row)
            guide?.currentLine=currentLine
            sendStatus("LINE "+(currentLine+1)+" COMPLETE • "+format(row.timestampMs ?: 0L))
            currentLine++
            accumulated.clear()
            guide?.currentLine=currentLine.coerceAtMost((layout?.lineCount?:1)-1)
            guide?.currentWordProgress=0
            if(currentLine>=(expected.size.takeIf{it>0} ?: layout?.lineCount ?: 0)){
                sendStatus("SCRIPT COMPLETE • press STOP then SAVE")
            }
        } else {
            val upcoming=findUpcoming(expected,currentLine,segment.text)
            if(upcoming>currentLine){
                for(i in currentLine until upcoming) lines.add(TimedScript(null,i+1,expected[i].text,0f,false))
                currentLine=upcoming
                accumulated.clear()
                sendStatus("RESYNCED TO LINE "+(currentLine+1)+" • skipped lines marked not detected")
            }
        }
    }

    private fun findUpcoming(expected:List<StoredScriptLine>,from:Int,text:String):Int{
        if(expected.isEmpty())return from
        var best=from;var bestScore=0f
        for(i in from..minOf(expected.lastIndex,from+3)){
            val s=similarity(text,expected[i].text)
            if(s>bestScore){bestScore=s;best=i}
        }
        return if(bestScore>=.55f)best else from
    }

    private fun progressMatched(expected:String,heard:String):Int{
        val a=FuzzyMatcher.tokens(expected);val b=FuzzyMatcher.tokens(heard);var p=0
        for(w in b){if(p<a.size && FuzzyMatcher.close(a[p],w))p++}
        return p
    }

    private fun similarity(a:String,b:String):Float{
        val aa=FuzzyMatcher.tokens(a).toSet();val bb=FuzzyMatcher.tokens(b).toSet()
        if(aa.isEmpty()||bb.isEmpty())return 0f
        return aa.intersect(bb).size.toFloat()/minOf(aa.size,bb.size).toFloat()
    }

    private fun guideWordCapacity()=20

    private fun stopRecording(){
        if(!recording)return
        stopAutoScroll()
        stopSubcomponents()
        recording=false
        guide?.recording=false
        sendStatus("STOPPED • timestamps kept in memory")
    }

    private fun savePdf(){
        if(saving)return
        saving=true
        stopAutoScroll()
        stopSubcomponents()
        recording=false
        guide?.recording=false
        val script=SessionStore.loadScript(this)
        val rows=if(script.isNotEmpty()){
            val byLine=lines.associateBy{it.lineNumber}
            script.map{base->
                val row=byLine[base.index]
                TimedScript(row?.timestampMs,base.index,base.text,row?.confidence?:0f,row?.detected==true)
            }
        } else lines.toList().sortedBy{it.lineNumber}
        if(rows.isEmpty()){saving=false;sendStatus("SAVE FAILED • no script lines or timestamps");return}
        val uri=TimestampPdfWriter.writeToDownloads(this,rows)
        if(uri==null){saving=false;sendStatus("SAVE FAILED • could not create PDF");return}
        SessionStore.saveLastPdf(this,uri)
        sendBroadcast(Intent(ACTION_PDF_READY).setPackage(packageName).putExtra(EXTRA_URI,uri.toString()))
        sendStatus("PDF SAVED • Downloads/ScriptTimestamper")
        hideAllOverlays()
        projection?.stop();projection=null
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }

    private fun startAutoScroll(){
        scrollHandler.removeCallbacks(scrollRunnable)
        if((layout?.scrollSpeed ?: 0)>0){
            scrollHandler.post(scrollRunnable)
        }
    }

    private fun stopAutoScroll(){
        scrollHandler.removeCallbacks(scrollRunnable)
    }

    private fun stopSubcomponents(){
        runCatching{ocr?.stop()};ocr=null
        runCatching{speech?.stop()};speech=null
    }

    private fun showBubble(){
        if(bubble!=null||!Settings.canDrawOverlays(this))return
        val b=TextView(this).apply{
            text="TG";textSize=12f;gravity=Gravity.CENTER;setTextColor(Color.BLACK);setBackgroundColor(Color.YELLOW)
            setOnClickListener{toggleMenu()}
            setOnTouchListener(DragListener())
        }
        val lp=WindowManager.LayoutParams(64,64,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.END;lp.x=10;lp.y=SessionStore.overlayY(this)
        wm.addView(b,lp);bubble=b
    }

    private fun toggleMenu(){
        if(menu!=null){removeMenu();return}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(8,8,8,8);setBackgroundColor(Color.WHITE)}
        addMenuButton(panel,"START"){startRecording()}
        addMenuButton(panel,"STOP"){stopRecording()}
        addMenuButton(panel,"SAVE"){savePdf()}
        addMenuButton(panel,"SET LINES"){showEditor()}
        val lp=WindowManager.LayoutParams(170,250,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.END;lp.x=85;lp.y=(SessionStore.overlayY(this)+72).coerceAtLeast(72)
        wm.addView(panel,lp);menu=panel
    }

    private fun addMenuButton(panel:LinearLayout,label:String,action:()->Unit){
        val b=TextView(this).apply{text=label;textSize=13f;gravity=Gravity.CENTER;setTextColor(Color.BLACK);setBackgroundColor(Color.YELLOW);setPadding(12,12,12,12);setOnClickListener{removeMenu();action()}}
        panel.addView(b,LinearLayout.LayoutParams(-1,48).apply{bottomMargin=6})
    }

    private fun showGuide(){
        if(guide!=null)return
        val g=LineGuideView(this);g.layout=layout
        val lp=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
        wm.addView(g,lp);guide=g
    }

    private fun showEditor(){
        removeMenu()
        if(editor!=null)return
        val e=LineLayoutEditorView(this)
        e.setLayout(layout?:SessionStore.loadLayout(this))
        e.listener={saved->
            layout=saved.normalized()
            SessionStore.saveLayout(this,layout!!)
            if(recording){startAutoScroll()}
            guide?.layout=layout
            hideEditor()
            sendStatus("LAYOUT SAVED • yellow lines remain visible")
        }
        val lp=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
        wm.addView(e,lp);editor=e
    }

    private fun hideEditor(){editor?.let{runCatching{wm.removeView(it)}};editor=null}
    private fun removeMenu(){menu?.let{runCatching{wm.removeView(it)}};menu=null}
    private fun hideAllOverlays(){hideEditor();removeMenu();bubble?.let{runCatching{wm.removeView(it)}};bubble=null;guide?.let{runCatching{wm.removeView(it)}};guide=null}

    override fun onDestroy(){stopAutoScroll();stopSubcomponents();hideAllOverlays();runCatching{projection?.stop()};projection=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null

    private fun createChannel(){
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("timestamp_genius","Timestamp Genius",NotificationManager.IMPORTANCE_LOW))
    }
    private fun sendStatus(s:String)=sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE,s))
    private fun format(ms:Long)="%02d:%02d:%02d.%03d".format(Locale.US,ms/3600000,(ms%3600000)/60000,(ms%60000)/1000,ms%1000)

    private inner class DragListener:View.OnTouchListener{
        var downX=0f;var downY=0f;var startX=0;var startY=0
        override fun onTouch(v:View,e:MotionEvent):Boolean{
            val lp=v.layoutParams as WindowManager.LayoutParams
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startX=lp.x;startY=lp.y;return true}
                MotionEvent.ACTION_MOVE->{lp.x=startX+(e.rawX-downX).toInt();lp.y=(startY+(e.rawY-downY)).toInt().coerceAtLeast(0);wm.updateViewLayout(v,lp);return true}
                MotionEvent.ACTION_UP->{SessionStore.saveOverlayY(this@CaptureService,lp.y);return true}
            }
            return false
        }
    }
}
