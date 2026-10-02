package com.timestampgenius

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class TimestampForegroundService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_START_SESSION = "start_session"
        const val ACTION_STOP_SESSION = "stop_session"
        const val ACTION_SAVE = "save"
        const val ACTION_SET_LINES = "set_lines"
    }

    private lateinit var store: SessionStore
    private lateinit var ocr: OcrEngine

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var analyzer: ScreenAnalyzer? = null
    private var audio: AudioPlaybackRecognizer? = null
    private var overlay: OverlayController? = null
    private var timerJob: Job? = null

    private var sessionStart = 0L
    private var currentLine = 0
    private var heardText = ""
    private var recognizedWords = 0

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        ocr = OcrEngine()
        createChannel()
        startForeground(1001, notification("Ready."))

        overlay = OverlayController(
            context = this,
            wm = getSystemService(WINDOW_SERVICE) as WindowManager,
            store = store,
            onStart = { startRecording() },
            onStop = { stopRecording() },
            onSave = { savePdf() }
        )

        if (Settings.canDrawOverlays(this)) overlay?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (data != null && code == Activity.RESULT_OK) {
                    startProjection(code, data)
                } else {
                    broadcast("Screen capture permission was not granted.")
                }
            }
            ACTION_STOP_SESSION -> stopRecording()
            ACTION_SAVE -> savePdf()
            ACTION_SET_LINES -> overlay?.openLineEditor()
        }
        return START_STICKY
    }

    private fun startProjection(code: Int, data: Intent) {
        if (projection != null) return

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        imageReader = ImageReader.newInstance(
            screenW,
            screenH,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                    releaseProjection()
                    broadcast("Screen capture ended.")
                }
            },
            Handler(Looper.getMainLooper())
        )

        try {
            projection?.createVirtualDisplay(
                "TimestampGeniusScreen",
                screenW,
                screenH,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                Handler(Looper.getMainLooper())
            )
        } catch (t: Throwable) {
            releaseProjection()
            broadcast("Could not start screen capture: " + (t.message ?: "unknown error"))
            return
        }

        broadcast("Timestamp Genius is ready. Configure lines, then press START on the floating icon.")
    }

    private fun startRecording() {
        if (projection == null) {
            broadcast("Open START on the main screen first and approve screen capture.")
            return
        }
        stopRecording()

        currentLine = 0
        heardText = ""
        recognizedWords = 0
        sessionStart = SystemClock.elapsedRealtime()

        store.setRunning(true)
        overlay?.setRecording(true)
        overlay?.setAutoStretch(false)

        timerJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && store.isRunning()) {
                val elapsed = SystemClock.elapsedRealtime() - sessionStart
                withContext(Dispatchers.Main) {
                    overlay?.updateTimer(format(elapsed))
                }
                delay(50)
            }
        }

        val layout = store.loadLayout()
        analyzer?.stop()
        analyzer = imageReader?.let { reader ->
            ScreenAnalyzer(
                imageReader = reader,
                layout = layout,
                ocr = ocr,
                currentLineProvider = { currentLine },
                onSnapshot = { snapshot ->
                    overlay?.updateRecognition(
                        snapshot.copy(glowCount = recognizedWords)
                    )
                },
                onTextLines = { lines ->
                    if (store.getPdfName() == null && lines.any { it.isNotBlank() }) {
                        store.appendScreenLines(lines)
                    }
                },
                onAutoStretch = { enabled ->
                    overlay?.setAutoStretch(enabled)
                    if (enabled) layout.boxH = 1f - layout.boxY
                }
            ).also { it.start() }
        }

        val language = chooseLanguage(store.getLines().map { it.text })
        audio = AudioPlaybackRecognizer(
            context = this,
            projection = projection!!,
            language = language,
            onPartial = { partial -> handleSpeech(partial, false, null) },
            onResult = { result, streamEndMs -> handleSpeech(result, true, streamEndMs) },
            onError = { message ->
                val userMessage =
                    if (message.contains("blocked", true) || message.contains("capture", true)) {
                        "Device audio capture is unavailable. The source app may block playback capture."
                    } else {
                        message
                    }
                broadcast(userMessage)
            }
        ).also { it.start() }

        autoScrollLoop(layout.scrollSpeed)
        broadcast("Recording started.")
    }

    private fun stopRecording() {
        val wasRunning = store.isRunning()
        store.setRunning(false)

        analyzer?.stop()
        analyzer = null

        audio?.stop()
        audio = null

        timerJob?.cancel()
        timerJob = null

        overlay?.setRecording(false)

        if (wasRunning) {
            broadcast("Recording stopped. Timestamps are kept in memory.")
        }
    }

    private fun handleSpeech(text: String, final: Boolean, audioEndMs: Long?) {
        if (!store.isRunning() || text.isBlank()) return

        if (!final) {
            val partialWords = TextMatching.tokens(text).size
            recognizedWords = maxOf(recognizedWords, partialWords)
            overlay?.updateGlowCount(partialWords, text)
            return
        }

        val lines = store.getLines()
        if (currentLine >= lines.size) return

        heardText = (heardText + " " + text).takeLast(1800)
        val expected = lines[currentLine].text
        recognizedWords = TextMatching.tokens(heardText).size

        val elapsed = SystemClock.elapsedRealtime() - sessionStart
        val timestamp = elapsed.coerceAtLeast(0L)
        val score = TextMatching.score(expected, heardText)
        val complete = TextMatching.completion(expected, heardText)

        if (complete && score >= 0.62f) {
            store.setTimestamp(currentLine, timestamp)
            currentLine++
            heardText = ""
            recognizedWords = 0

            broadcast(
                "Line " + currentLine + " timestamped at " + format(timestamp) + "."
            )
            overlay?.updateGlowCount(0, "")
            return
        }

        if (currentLine < lines.size - 1) {
            val upcoming = lines.drop(currentLine + 1).take(3)
            val hit = upcoming.indexOfFirst {
                TextMatching.score(it.text, text) >= 0.70f
            }

            if (hit >= 0) {
                repeat(hit + 1) {
                    store.markNotDetected(currentLine)
                    currentLine++
                }
                heardText = text
                recognizedWords = TextMatching.tokens(text).size
                broadcast("Skipped script line(s) marked not detected.")
            }
        }

        overlay?.updateGlowCount(
            recognizedWords,
            text
        )
    }

    private fun autoScrollLoop(speed: Int) {
        if (speed <= 0) return

        CoroutineScope(Dispatchers.Default).launch {
            while (store.isRunning() && projection != null) {
                TimestampAccessibilityService.instance?.scrollUp(
                    0.08f + speed * 0.03f
                )
                delay((1200L - speed * 95L).coerceAtLeast(250L))
            }
        }
    }

    private fun savePdf() {
        stopRecording()

        val lines = store.getLines()
        if (lines.isEmpty()) {
            broadcast("There is no script to save.")
            return
        }

        PdfTimestampWriter(this)
            .write(lines)
            .onSuccess { (uri, name) ->
                store.setLastPdf(uri.toString(), name)
                broadcast(
                    "Saved " + name + " to Downloads/ScriptTimestamper/",
                    uri.toString()
                )
            }
            .onFailure {
                broadcast(
                    "Could not save the PDF: " + (it.message ?: "unknown error")
                )
            }
    }

    private fun chooseLanguage(lines: List<String>): String {
        val devanagari = lines.sumOf {
            it.count { c -> c in '\u0900'..'\u097F' }
        }
        val latinLetters = lines.sumOf {
            it.count { c -> c.isLetterOrDigit() }
        }
        return if (devanagari > latinLetters * 0.08) "hi" else "en"
    }

    private fun releaseProjection() {
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        stopRecording()
        releaseProjection()
        overlay?.hide()
        ocr.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcast(message: String, savedUri: String? = null) {
        sendBroadcast(
            Intent(AppState.ACTION_STATUS).apply {
                setPackage(packageName)
                putExtra(AppState.EXTRA_MESSAGE, message)
                putExtra(AppState.EXTRA_RUNNING, store.isRunning())
                if (savedUri != null) {
                    putExtra(AppState.EXTRA_SAVED_URI, savedUri)
                }
            }
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            "timestamp",
            "Timestamp Genius",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "timestamp")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Timestamp Genius")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1_000
        val milli = total % 1_000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            h,
            m,
            s,
            milli
        )
    }
}
