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
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class CaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STATUS = "capture_status"
        const val ACTION_PDF_READY = "pdf_ready"
        const val ACTION_STOP = "stop_capture"
        const val ACTION_FRESH = "fresh_capture"
        const val ACTION_START = "start_capture"
        const val ACTION_SAVE = "save_capture"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_URI = "uri"
    }

    private lateinit var wm: WindowManager
    private var overlayContext: Context? = null
    private var bubble: View? = null
    private var editor: View? = null
    private var projection: MediaProjection? = null
    private var ocr: ScreenOcrCapture? = null
    private var speech: LiveSpeechTimer? = null
    private var layout: LineLayout? = null
    private var latestLines: List<ScreenLine> = emptyList()
    private val matches = mutableListOf<TimedScript>()
    private var running = false

    override fun onCreate() {
        super.onCreate()
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            val dm = getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        wm = overlayContext!!.getSystemService(WindowManager::class.java)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("capture", "Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) { startCaptureListening(); return START_NOT_STICKY }\n        if (intent?.action == ACTION_STOP) { stopListeningOnly(); return START_NOT_STICKY }\n        if (intent?.action == ACTION_SAVE) { savePdfOnly(); return START_NOT_STICKY }\n        when (intent?.action) {
            ACTION_STOP -> { finishCapture(); return START_NOT_STICKY }
            ACTION_FRESH -> {
                matches.clear()
                latestLines = emptyList()
                layout = null
                sendStatus("Fresh session ready")
                return START_STICKY
            }
        }

        if (!running) {
            startForegroundNotification()
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            }
            if (resultCode == Activity.RESULT_OK && data != null) startCapture(resultCode, data)
            else { sendStatus("Screen capture permission was not granted"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Audio Timestamp Studio")
            .setContentText("Screen reading and audio listening are active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        ServiceCompat.startForeground(
            this, 77, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
        if (projection == null) { sendStatus("Unable to create screen capture"); stopSelf(); return }
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { sendStatus("Screen capture permission ended"); savePdfOnly() }
        }, Handler(Looper.getMainLooper()))
        running = true
        showBubble()
        showGuide()
        sendStatus("READY • set lines, then press START")
    }

    private fun startCaptureListening() {
        if (!running) return
        if (layout == null) { sendStatus("SET LINES and SAVE LAYOUT first"); return }
        if (ocr != null || speech != null) return

        ocr = ScreenOcrCapture(this, projection!!, { layout }) { lines ->
            latestLines = lines
            guide?.lineTexts = lines
        }
        ocr!!.start()

        val systemLocale = Locale.getDefault()
        val speechLocale = if (systemLocale.language.equals("hi", true)) {
            Locale.forLanguageTag("hi-IN")
        } else {
            Locale.forLanguageTag("en-IN")
        }

        speech = LiveSpeechTimer(
            context = this,
            projection = projection,
            locale = speechLocale,
            onPartial = { text, _ ->
                val preview = TimestampEngine.match(
                    latestLines,
                    SpokenSegment(0L, text, 1f),
                    emptySet()
                )
                guide?.highlightLine = preview?.panelNumber ?: -1
            },
            onSegment = { segment ->
                val used = matches.map { it.panelNumber }.toSet()
                val match = TimestampEngine.match(latestLines, segment, used)
                if (match != null && matches.none { it.panelNumber == match.panelNumber }) {
                    matches.add(match)
                    guide?.highlightLine = match.panelNumber
                    sendStatus("MATCHED line " + match.panelNumber + " at " + format(match.timestampMs))
                }
            },
            onStatus = { status -> sendStatus(status) }
        )
        speech!!.start()
        guide?.running = true
        sendStatus("STARTED • live screen OCR + internal audio reader")
    }

    private fun stopListeningOnly() {
        ocr?.stop(); speech?.stop(); ocr = null; speech = null
        guide?.running = false
        guide?.highlightLine = -1
        sendStatus("STOPPED • press START to continue or SAVE to create PDF")
    }

    private fun savePdfOnly() {
        ocr?.stop(); speech?.stop(); ocr = null; speech = null
        val rows = TimestampEngine.ordered(matches)
        sendStatus("SAVING • creating timestamp PDF")
        val uri = TimestampPdfWriter.writeToDownloads(this, rows)
        if (uri != null) {
            sendBroadcast(Intent(ACTION_PDF_READY).setPackage(packageName).putExtra(EXTRA_URI, uri.toString()))
            sendStatus("SAVED • PDF is in Downloads/Audio Timestamp Studio")
        } else {
            sendStatus("SAVE FAILED • PDF could not be written")
        }
        hideEditor()
        bubble?.let { runCatching { wm.removeView(it) } }; bubble = null
        guide?.let { runCatching { wm.removeView(it) } }; guide = null
        projection?.stop(); projection = null; running = false
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val c = overlayContext ?: this
        val box = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6),dp(5),dp(6),dp(5)); setBackgroundColor(0xEE121A2B.toInt()) }
        fun b(t:String,col:Int,action:()->Unit)=TextView(c).apply {
            text=t; textSize=12f; setTextColor(if(t=="SET LINES") Color.BLACK else Color.WHITE); gravity=Gravity.CENTER
            setPadding(dp(9),dp(9),dp(9),dp(9)); background=rounded(col,16f); setOnClickListener{action()}
        }
        box.addView(b("START",0xFF20B26B.toInt()){startCaptureListening()}); box.addView(space(4))
        box.addView(b("STOP",0xFFE53935.toInt()){stopListeningOnly()}); box.addView(space(4))
        box.addView(b("SAVE",0xFF3F51B5.toInt()){savePdfOnly()}); box.addView(space(4))
        box.addView(b("SET LINES",0xFFFFC107.toInt()){showEditor()})
        val p=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
        p.gravity=Gravity.TOP or Gravity.END; p.x=dp(8); p.y=dp(70)
        wm.addView(box,p); bubble=box
    }

    private fun showGuide() {
        if (guide != null) return
        guide = LineGuideView(overlayContext ?: this)
        guide?.layout = layout
        val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT)
        wm.addView(guide,p)
    }

    private fun showEditor() {
        if (editor != null) return
        val c=overlayContext?:this
        val root=FrameLayout(c)
        val drawing=LineLayoutEditorView(c)
        drawing.lineCount=layout?.lineCount?:5
        root.addView(drawing,FrameLayout.LayoutParams(-1,-1))
        drawing.post { drawing.setExistingLayout(layout, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) }

        val panel=LinearLayout(c).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xF5101827.toInt())
        }
        panel.addView(TextView(c).apply {
            text="CUSTOMIZE YELLOW BOX"
            textSize=18f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0,0,0,dp(4))
        })
        panel.addView(TextView(c).apply {
            text="Drag inside to MOVE • drag edges/corners to RESIZE"
            textSize=13f
            setTextColor(0xFFE1E7F4.toInt())
            setPadding(0,0,0,dp(8))
        })
        val row=LinearLayout(c).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val count=TextView(c).apply {
            text="LINES: "+drawing.lineCount
            textSize=16f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
        }
        row.addView(action(c,"−"){ drawing.lineCount--; count.text="LINES: "+drawing.lineCount },
            LinearLayout.LayoutParams(0,dp(52),1f))
        row.addView(count,LinearLayout.LayoutParams(dp(120),dp(52)))
        row.addView(action(c,"+"){ drawing.lineCount++; count.text="LINES: "+drawing.lineCount },
            LinearLayout.LayoutParams(0,dp(52),1f))
        panel.addView(row)

        val buttons=LinearLayout(c).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(0,dp(8),0,0)
        }
        buttons.addView(action(c,"SET LAYOUT / SAVE") {
            val m=drawing.toLayout(resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
            if(m==null) {
                Toast.makeText(this,"Draw a larger box first",Toast.LENGTH_SHORT).show()
            } else {
                layout=m
                guide?.layout=m
                hideEditor()
                sendStatus("LAYOUT SAVED • yellow lines locked on screen")
            }
        },LinearLayout.LayoutParams(0,dp(56),1f))
        buttons.addView(action(c,"CLOSE EDITOR") {
            hideEditor()
            sendStatus("EDITOR CLOSED • layout unchanged")
        },LinearLayout.LayoutParams(0,dp(56),1f))
        panel.addView(buttons)
        val top=FrameLayout.LayoutParams(-1,FrameLayout.LayoutParams.WRAP_CONTENT)
        top.gravity=Gravity.TOP
        root.addView(panel,top)
        val lp=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        wm.addView(root,lp); editor=root
    }

    private fun hideEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideEditor()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { ocr?.stop() }
        runCatching { speech?.stop() }
        projection?.stop()
        projection = null
        running = false
        super.onDestroy()
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    private fun action(c: Context, text: String, click: () -> Unit): TextView = TextView(c).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(0xFF6C4DFF.toInt(), 18f)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
    }

    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
    }

    private fun space(w: Int) = Space(overlayContext ?: this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(w), 1)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun format(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
