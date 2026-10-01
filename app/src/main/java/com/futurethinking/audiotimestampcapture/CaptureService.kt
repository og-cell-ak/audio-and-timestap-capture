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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STATUS = "capture_status"
        const val ACTION_PDF_READY = "pdf_ready"
        const val ACTION_STOP = "stop_capture"
        const val ACTION_FRESH = "fresh_capture"
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
        when (intent?.action) {
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
            override fun onStop() { sendStatus("Screen capture permission ended"); finishCapture() }
        }, Handler(Looper.getMainLooper()))

        running = true
        showBubble()

        ocr = ScreenOcrCapture(this, projection!!, { layout }) { lines ->
            latestLines = lines
            val visible = lines.joinToString("  •  ") { "L\${it.index}: \${it.text}" }
            sendStatus(if (visible.isBlank()) "Reading selected lines..." else "Reading: $visible")
        }
        ocr!!.start()

        speech = LiveSpeechTimer(this) { segment ->
            val matchedIds = matches.map { it.panelNumber }.toSet()
            val match = TimestampEngine.match(latestLines, segment, matchedIds)
            if (match != null && matches.none { it.panelNumber == match.panelNumber }) {
                matches.add(match)
                sendStatus("MATCHED line \${match.panelNumber} at \${format(match.timestampMs)}")
            }
        }
        speech!!.start()
        sendStatus("RECORDING • screen reader + audio listener active")
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        val c = overlayContext ?: this
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setBackgroundColor(0xDD121A2B.toInt())
        }

        val linesButton = TextView(c).apply {
            text = "● SET LINES"
            textSize = 13f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFFFFC107.toInt(), 18f)
            setOnClickListener { showEditor() }
        }
        val stopButton = TextView(c).apply {
            text = "STOP"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xFFE53935.toInt(), 18f)
            setOnClickListener { finishCapture() }
        }
        box.addView(linesButton)
        box.addView(space(6))
        box.addView(stopButton)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.END
        p.x = dp(8)
        p.y = dp(70)
        wm.addView(box, p)
        bubble = box
    }

    private fun showEditor() {
        if (editor != null) return
        val c = overlayContext ?: this
        val root = FrameLayout(c)
        root.setBackgroundColor(0x55000000)
        val drawing = LineLayoutEditorView(c)
        drawing.lineCount = layout?.lineCount ?: 5
        root.addView(drawing, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xEE101827.toInt())
        }
        panel.addView(TextView(c).apply {
            text = "DRAW YOUR BOX • equal sentence lines"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val controls = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val minus = action(c, "−") { drawing.lineCount--; count.text = "LINES: \${drawing.lineCount}" }
        val count = TextView(c).apply {
            text = "LINES: \${drawing.lineCount}"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
        }
        val plus = action(c, "+") { drawing.lineCount++; count.text = "LINES: \${drawing.lineCount}" }
        controls.addView(minus)
        controls.addView(count)
        controls.addView(plus)
        panel.addView(controls)

        val buttons = LinearLayout(c)
        val save = action(c, "SAVE LAYOUT") {
            val m = drawing.toLayout(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            if (m == null) Toast.makeText(this, "Draw a box first", Toast.LENGTH_SHORT).show()
            else {
                layout = m
                sendStatus("Layout saved • \${m.lineCount} equal lines")
                hideEditor()
            }
        }
        val cancel = action(c, "CANCEL") { hideEditor() }
        buttons.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(buttons)
        val top = FrameLayout.LayoutParams(-1, dp(155))
        top.gravity = Gravity.TOP
        root.addView(panel, top)

        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(root, lp)
        editor = root
    }

    private fun hideEditor() {
        editor?.let { runCatching { wm.removeView(it) } }
        editor = null
    }

    private fun finishCapture() {
        if (!running) { stopSelf(); return }
        running = false
        sendStatus("STOPPING • building timestamp PDF")
        ocr?.stop()
        speech?.stop()
        ocr = null
        speech = null
        hideEditor()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        projection?.stop()
        projection = null

        val rows = TimestampEngine.ordered(matches)
        val uri = TimestampPdfWriter.writeToDownloads(this, rows)
        if (uri != null) {
            sendBroadcast(Intent(ACTION_PDF_READY).setPackage(packageName).putExtra(EXTRA_URI, uri.toString()))
            sendStatus("DONE • timestamp PDF saved to Downloads/Audio Timestamp Studio")
        } else {
            sendStatus(if (rows.isEmpty()) "DONE • no confirmed speech-to-line matches, so no PDF was written" else "PDF save failed")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
