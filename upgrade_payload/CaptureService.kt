package com.futurethinking.audiotimestampcapture

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE="result_code"
        const val EXTRA_RESULT_DATA="result_data"
        const val ACTION_STATUS="capture_status"
        const val ACTION_PDF_READY="pdf_ready"
        const val ACTION_STOP="stop_capture"
        const val ACTION_SAVE="save_capture"
        const val ACTION_START="start_capture"
        const val ACTION_FRESH="fresh_capture"
        const val EXTRA_MESSAGE="message"
        const val EXTRA_URI="uri"
    }
    private lateinit var wm:WindowManager
    private var overlayContext:Context?=null
    private var bubble:View?=null
    private var editor:View?=null
    private var guide:LineGuideView?=null
    private var projection:MediaProjection?=null
    private var ocr:ScreenOcrCapture?=null
    private var speech:LiveSpeechTimer?=null
    private var layout:LineLayout?=null
    private var latestLines:List<ScreenLine> = emptyList()
    private val matches=mutableListOf<TimedScript>()
    private var sessionActive=false
    private var listening=false
    private var pdfSaved=false

    override fun onCreate(){
        super.onCreate()
        overlayContext=if(Build.VERSION.SDK_INT>=30){
            val dm=getSystemService(DisplayManager::class.java)
            createDisplayContext(dm.getDisplay(Display.DEFAULT_DISPLAY)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
        }else this
        wm=overlayContext!!.getSystemService(WindowManager::class.java)
        if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("capture","Capture",NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            ACTION_START->{startListening();return START_NOT_STICKY}
            ACTION_STOP->{stopListening();return START_NOT_STICKY}
            ACTION_SAVE->{saveAndFinish();return START_NOT_STICKY}
            ACTION_FRESH->{resetSession();return START_NOT_STICKY}
        }
        if(projection==null){
            val n=NotificationCompat.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Audio Timestamp Studio").setContentText("Ready. Press START.").setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
            ServiceCompat.startForeground(this,77,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val code=intent?.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED)?:Activity.RESULT_CANCELED
            val data=if(Build.VERSION.SDK_INT>=33)intent?.getParcelableExtra(EXTRA_RESULT_DATA,Intent::class.java)else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if(code==Activity.RESULT_OK&&data!=null)startSession(code,data)else stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(code:Int,data:Intent){
        projection=(getSystemService(MEDIA_PROJECTION_SERVICE)as MediaProjectionManager).getMediaProjection(code,data)
        if(projection==null){sendStatus("Unable to create screen capture");stopSelf();return}
        projection!!.registerCallback(object:MediaProjection.Callback(){override fun onStop(){saveAndFinish()}},Handler(Looper.getMainLooper()))
        sessionActive=true
        showGuide()
        showBubble()
        sendStatus("READY • set lines, then press START")
    }

    private fun startListening(){
        if(!sessionActive||listening)return
        val l=layout
        if(l==null){sendStatus("SET LINES and SAVE LAYOUT first");return}
        listening=true
        ocr=ScreenOcrCapture(this,projection!!,{layout}){lines->
            latestLines=lines
            guide?.lineTexts=lines
        }
        ocr!!.start()
        speech=LiveSpeechTimer(this,{partial,ms->
            val used=matches.map{it.panelNumber}.toSet()
            val line=TimestampEngine.bestLine(latestLines,partial,used)
            guide?.highlightLine=line?.index?:-1
            guide?.highlightWord=partial.split(Regex("\\s+")).lastOrNull().orEmpty()
        }){segment->
            val used=matches.map{it.panelNumber}.toSet()
            TimestampEngine.match(latestLines,segment,used)?.let{match->
                if(matches.none{it.panelNumber==match.panelNumber})matches.add(match)
                guide?.highlightLine=match.panelNumber
                guide?.highlightWord=match.script.split(Regex("\\s+")).firstOrNull().orEmpty()
            }
        }
        speech!!.start()
        sendStatus("STARTED • reading screen and listening")
    }

    private fun stopListening(){
        if(!listening)return
        listening=false
        ocr?.stop();speech?.stop();ocr=null;speech=null
        guide?.highlightLine=-1;guide?.highlightWord=""
        sendStatus("STOPPED • press SAVE to create PDF")
    }

    private fun showBubble(){
        if(!Settings.canDrawOverlays(this)||bubble!=null)return
        val c=overlayContext?:this
        val box=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(6),dp(5),dp(6),dp(5));setBackgroundColor(0xEE121A2B.toInt())}
        fun b(t:String,col:Int,action:()->Unit)=TextView(c).apply{text=t;textSize=12f;setTextColor(if(col==0xFFFFC107.toInt())Color.BLACK else Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(9),dp(9),dp(9),dp(9));background=rounded(col);setOnClickListener{action()}}
        box.addView(b("START",0xFF20B26B.toInt()){startListening()});box.addView(space())
        box.addView(b("STOP",0xFFE53935.toInt()){stopListening()});box.addView(space())
        box.addView(b("SAVE",0xFF3F51B5.toInt()){saveAndFinish()});box.addView(space())
        box.addView(b("SET LINES",0xFFFFC107.toInt()){showEditor()})
        wm.addView(box,WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.END;x=dp(8);y=dp(70)})
        bubble=box
    }

    private fun showGuide(){
        if(guide!=null)return
        guide=LineGuideView(overlayContext?:this)
        wm.addView(guide,WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT))
    }

    private fun showEditor(){
        if(editor!=null)return
        val c=overlayContext?:this
        val root=FrameLayout(c)
        val drawing=LineLayoutEditorView(c)
        drawing.lineCount=layout?.lineCount?:5
        root.addView(drawing,FrameLayout.LayoutParams(-1,-1))
        drawing.post{drawing.setExistingLayout(layout,width,height)}
        val panel=LinearLayout(c).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));setBackgroundColor(0xEE101827.toInt())}
        panel.addView(TextView(c).apply{text="CUSTOMIZE BOX • drag inside to move • drag edges/corners to resize";setTextColor(Color.WHITE);textSize=14f})
        val row=LinearLayout(c).apply{gravity=Gravity.CENTER_VERTICAL}
        val count=TextView(c).apply{text="LINES: "+drawing.lineCount;textSize=16f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(16),0,dp(16),0)}
        row.addView(action(c,"−"){drawing.lineCount--;count.text="LINES: "+drawing.lineCount})
        row.addView(count)
        row.addView(action(c,"+"){drawing.lineCount++;count.text="LINES: "+drawing.lineCount})
        panel.addView(row)
        panel.addView(action(c,"SAVE LAYOUT"){
            val m=drawing.toLayout(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
            if(m==null)Toast.makeText(this,"Draw a box first",Toast.LENGTH_SHORT).show()
            else{layout=m;guide?.layout=m;hideEditor();sendStatus("LAYOUT SAVED • yellow lines stay visible")}
        })
        root.addView(panel,FrameLayout.LayoutParams(-1,dp(160)).apply{gravity=Gravity.TOP})
        wm.addView(root,WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT))
        editor=root
    }

    private fun hideEditor(){editor?.let{runCatching{wm.removeView(it)}};editor=null}

    private fun saveAndFinish(){
        if(pdfSaved)return
        stopListening()
        sendStatus("SAVING • creating PDF")
        val uri=TimestampPdfWriter.writeToDownloads(this,TimestampEngine.ordered(matches))
        if(uri!=null){
            pdfSaved=true
            sendBroadcast(Intent(ACTION_PDF_READY).setPackage(packageName).putExtra(EXTRA_URI,uri.toString()))
            sendStatus("SAVED • PDF in Downloads/Audio Timestamp Studio")
        }else sendStatus("SAVE FAILED • PDF could not be written")
        hideEditor()
        guide?.let{runCatching{wm.removeView(it)}};guide=null
        bubble?.let{runCatching{wm.removeView(it)}};bubble=null
        projection?.stop();projection=null;sessionActive=false
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }

    private fun resetSession(){stopListening();matches.clear();latestLines=emptyList();layout=null;pdfSaved=false;guide?.layout=null;sendStatus("FRESH SESSION READY")}
    private fun action(c:Context,t:String,click:()->Unit)=TextView(c).apply{text=t;textSize=13f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0);background=rounded(0xFF6C4DFF.toInt());setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(0,dp(46),1f)}
    private fun rounded(col:Int)=android.graphics.drawable.GradientDrawable().apply{setColor(col);cornerRadius=dp(16).toFloat()}
    private fun space()=Space(overlayContext?:this).apply{layoutParams=LinearLayout.LayoutParams(dp(4),1)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun sendStatus(s:String)=sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE,s))
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){runCatching{ocr?.stop()};runCatching{speech?.stop()};hideEditor();guide?.let{runCatching{wm.removeView(it)}};bubble?.let{runCatching{wm.removeView(it)}};projection?.stop();super.onDestroy()}
}
