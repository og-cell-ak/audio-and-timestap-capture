package com.futurethinking.timestampgenius.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.futurethinking.timestampgenius.LineShape
import com.futurethinking.timestampgenius.LayoutConfig
import com.futurethinking.timestampgenius.SessionStore
import com.futurethinking.timestampgenius.audio.PlaybackAudioCapture
import com.futurethinking.timestampgenius.ocr.OcrWord
import com.futurethinking.timestampgenius.ocr.ScriptOcr
import com.futurethinking.timestampgenius.pdf.TimestampPdfWriter
import com.futurethinking.timestampgenius.util.FuzzyMatcher
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class TimestampOverlayService : Service() {

    companion object {
        const val ACTION_PREPARE = "com.futurethinking.timestampgenius.PREPARE"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val CHANNEL_ID = "timestamp_genius_capture"
        private const val NOTIFICATION_ID = 91
    }

    private lateinit var store: SessionStore
    private lateinit var windowManager: WindowManager
    private lateinit var guide: GuideOverlay

    private var controls: OverlayControls? = null
    private var editor: LineEditorView? = null
    private var projection: MediaProjection? = null
    private var imageReader: android.media.ImageReader? = null
    private var display: VirtualDisplay? = null
    private var audioCapture: PlaybackAudioCapture? = null

    private var recording = false
    private var startedAt = 0L
    private var currentLine = 0
    private var recognizedText = ""
    private var partialText = ""
    private var language = "en"
    private val ocrBusy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var scrollRunnable: Runnable? = null
    private val discoveredScreenLines = ArrayList<String>()
    private var screenMode = false

    override fun onCreate() {
        super.onCreate()
        store = SessionStore.get(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundCaptureNotification()

        guide = GuideOverlay(this, store.layout.value)
        addGuideOverlay()
        addControlOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREPARE) {
            val projectionData =
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_PROJECTION_DATA,
                        Intent::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }

            if (projectionData != null) {
                prepareProjection(projectionData)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording(false)
        removeEditor()
        removeControls()
        removeGuideOverlay()
        runCatching { projection?.stop() }
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Timestamp Genius capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun startForegroundCaptureNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Timestamp Genius")
            .setContentText("Floating timestamp controls are ready")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun prepareProjection(data: Intent) {
        runCatching { projection?.stop() }

        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val newProjection = manager.getMediaProjection(Activity.RESULT_OK, data)
            projection = newProjection

            newProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        main.post {
                            stopRecording(false)
                            Toast.makeText(
                                this@TimestampOverlayService,
                                "Screen capture permission ended.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                main
            )
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                "Could not prepare screen capture: " +
                    (error.message ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun addGuideOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(guide, params)
    }

    private fun addControlOverlay() {
        if (!Settings.canDrawOverlays(this) || controls != null) return

        controls = OverlayControls(
            this,
            object : OverlayControls.Callbacks {
                override fun onStart() = startRecording()
                override fun onStop() = stopRecording(true)
                override fun onSave() = savePdf()
                override fun onSetLines() = openEditor()
            }
        )

        val overlayPrefs = getSharedPreferences("timestamp_genius_overlay", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            dp(88),
            dp(330),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayPrefs.getInt("x", dp(12))
            y = overlayPrefs.getInt("y", dp(120))
        }

        controls?.layoutParams = params
        windowManager.addView(controls, params)
    }

    private fun removeGuideOverlay() {
        runCatching { windowManager.removeView(guide) }
    }

    private fun removeControls() {
        controls?.let { runCatching { windowManager.removeView(it) } }
        controls = null
    }

    private fun openEditor() {
        if (recording) {
            Toast.makeText(
                this,
                "Stop recording before changing the layout.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (editor != null) return

        editor = LineEditorView(
            this,
            store.layout.value,
            onSave = { config ->
                store.setLayout(config)
                guide.update(config)
                Toast.makeText(
                    this,
                    "Layout saved. Yellow lines remain visible.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onClose = { removeEditor() }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(editor, params)
    }

    private fun removeEditor() {
        editor?.let { runCatching { windowManager.removeView(it) } }
        editor = null
    }

    private fun startRecording() {
        if (recording) return

        if (!store.layoutReady) {
            Toast.makeText(
                this,
                "Open SET LINES and save the layout before recording.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val activeProjection = projection
        if (activeProjection == null) {
            Toast.makeText(
                this,
                "Tap START on the main app first and grant capture permission.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        currentLine = 0
        recognizedText = ""
        partialText = ""
        discoveredScreenLines.clear()
        screenMode = store.scriptLines.value.isEmpty()
        startedAt = SystemClock.elapsedRealtime()
        recording = true
        guide.setRecording(true)
        store.refreshTimestampLines()

        language = chooseLanguage(store.scriptLines.value)
        audioCapture = PlaybackAudioCapture(
            this,
            activeProjection,
            language,
            onText = { text, isFinal ->
                main.post { handleSpeech(text, isFinal) }
            },
            onError = { message ->
                main.post {
                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        val audioStarted = audioCapture?.start() == true
        if (!audioStarted) {
            stopRecording(false)
            return
        }

        startScreenCapture()

        if (store.layout.value.scrollSpeed > 0 && !isAccessibilityEnabled()) {
            Toast.makeText(
                this,
                "Auto-scroll needs Accessibility access. Recording will continue without auto-scroll.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            startAutoScroll()
        }

        Toast.makeText(
            this,
            "Recording started at 00:00:00.000",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun chooseLanguage(lines: List<String>): String {
        if (lines.isEmpty()) return "en"
        val source = lines.joinToString(" ")
        val devanagari = source.count { it in 'ऀ'..'ॿ' }
        val letters = source.count { it.isLetter() }
        return if (letters > 0 && devanagari.toFloat() / letters.toFloat() > 0.15f) "hi" else "en"
    }

    private fun startScreenCapture() {
        val metrics = resources.displayMetrics
        val reader = android.media.ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            if (!recording || !ocrBusy.compareAndSet(false, true)) return@setOnImageAvailableListener

            val image = source.acquireLatestImage()
            if (image == null) {
                ocrBusy.set(false)
                return@setOnImageAvailableListener
            }

            worker.execute {
                try {
                    val bitmap = imageToBitmap(image)
                    image.close()
                    val words = ScriptOcr.recognize(bitmap)
                    bitmap.recycle()
                    main.post { handleOcr(words) }
                } catch (_: Throwable) {
                    runCatching { image.close() }
                } finally {
                    ocrBusy.set(false)
                }
            }
        }, main)

        display = projection?.createVirtualDisplay(
            "TimestampGenius",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            main
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val width = image.width
        val height = image.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val padded = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        val cropped = Bitmap.createBitmap(
            padded,
            0,
            0,
            width,
            height
        )

        padded.recycle()
        return cropped
    }

    private fun handleOcr(words: List<OcrWord>) {
        val config = store.layout.value
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val top = config.boxTop * screenHeight
        val bottom = (config.boxTop + config.boxHeight).coerceAtMost(1f) * screenHeight
        val gap = (bottom - top) / config.lineCount.coerceAtLeast(1)

        val rows = Array(config.lineCount) { ArrayList<OcrWord>() }

        for (word in words) {
            val centerY = (word.top + word.bottom) / 2f
            if (centerY in top..bottom) {
                val row = ((centerY - top) / gap)
                    .toInt()
                    .coerceIn(0, config.lineCount - 1)
                rows[row].add(word)
            }
        }

        val visibleLines = rows.map { row ->
            row.sortedBy { it.left }
                .joinToString(" ") { it.text }
                .trim()
        }

        if (screenMode) {
            mergeScreenLines(visibleLines)
        }

        val expectedLines = store.scriptLines.value
        if (expectedLines.isNotEmpty() && currentLine in expectedLines.indices) {
            val progress = FuzzyMatcher.progress(
                expectedLines[currentLine],
                recognizedText + " " + partialText
            )
            val expectedWords = FuzzyMatcher.normalize(expectedLines[currentLine]).size
            guide.highlight(
                currentLine,
                (progress * expectedWords).roundToInt(),
                rows[currentLine]
            )
        }
    }

    private fun mergeScreenLines(visible: List<String>) {
        val clean = visible.filter { it.isNotBlank() }

        for (candidate in clean) {
            val nearExisting = discoveredScreenLines.any {
                FuzzyMatcher.similarity(it, candidate) >= 0.86f
            }
            if (!nearExisting) discoveredScreenLines.add(candidate)
        }

        if (discoveredScreenLines.isNotEmpty()) {
            val current = store.scriptLines.value
            if (discoveredScreenLines.size > current.size) {
                store.replaceScript(discoveredScreenLines.toList())
            }
        }
    }

    private fun handleSpeech(text: String, isFinal: Boolean) {
        if (!recording || text.isBlank()) return

        if (isFinal) {
            recognizedText = (recognizedText + " " + text)
                .trim()
                .takeLast(4000)
            partialText = ""
        } else {
            partialText = text
        }

        val lines = store.scriptLines.value
        if (currentLine !in lines.indices) return

        val combined = recognizedText + " " + partialText
        val score = FuzzyMatcher.progress(lines[currentLine], combined)
        val wordCount = FuzzyMatcher.normalize(lines[currentLine]).size
        val threshold = when {
            wordCount <= 3 -> 0.78f
            wordCount <= 7 -> 0.84f
            else -> 0.88f
        }

        if (score >= threshold) {
            completeCurrentLine()
        } else {
            resynchronize(lines, combined)
        }
    }

    private fun resynchronize(lines: List<String>, spoken: String) {
        var bestIndex = -1
        var bestScore = 0f

        for (index in currentLine + 1 until minOf(lines.size, currentLine + 5)) {
            val score = FuzzyMatcher.progress(lines[index], spoken)
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        if (bestIndex >= 0 && bestScore >= 0.78f) {
            currentLine = bestIndex
            guide.setCurrentLine(currentLine)
            recognizedText = ""
            partialText = ""
        }
    }

    private fun completeCurrentLine() {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        store.recordTimestamp(currentLine, elapsed)
        currentLine += 1
        guide.setCurrentLine(currentLine)
        recognizedText = ""
        partialText = ""

        if (currentLine >= store.scriptLines.value.size) {
            stopRecording(false)
            Toast.makeText(
                this,
                "All script lines were detected.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun startAutoScroll() {
        stopAutoScroll()

        if (store.layout.value.scrollSpeed <= 0) return
        if (!isAccessibilityEnabled()) return

        val runnable = object : Runnable {
            override fun run() {
                if (!recording) return
                val steps = ((store.layout.value.scrollSpeed + 1) / 2).coerceAtLeast(1)
                repeat(steps) {
                    ScriptAccessibilityService.scrollForward()
                }
                main.postDelayed(
                    this,
                    (1000L - store.layout.value.scrollSpeed * 90L)
                        .coerceAtLeast(180L)
                )
            }
        }

        scrollRunnable = runnable
        main.post(runnable)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.contains(packageName, ignoreCase = true) }
    }

    private fun stopAutoScroll() {
        scrollRunnable?.let(main::removeCallbacks)
        scrollRunnable = null
    }

    private fun stopRecording(showToast: Boolean) {
        val wasRecording = recording
        recording = false
        stopAutoScroll()

        audioCapture?.stop()
        audioCapture = null

        runCatching { display?.release() }
        display = null
        runCatching { imageReader?.close() }
        imageReader = null

        guide.setRecording(false)

        if (showToast && wasRecording) {
            Toast.makeText(
                this,
                "Recording stopped. Timestamps are kept.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun savePdf() {
        val lines = store.timestamps.value
        if (lines.isEmpty()) {
            Toast.makeText(
                this,
                "No script lines are available to save.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        worker.execute {
            try {
                val uri = TimestampPdfWriter.write(
                    this,
                    lines,
                    store.timestampFileName()
                )
                store.setLastPdf(uri)
                main.post {
                    Toast.makeText(
                        this,
                        "Saved PDF in Downloads/ScriptTimestamper",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Throwable) {
                main.post {
                    Toast.makeText(
                        this,
                        "PDF save failed: " +
                            (error.message ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private class GuideOverlay(
        context: Context,
        private var config: LayoutConfig
    ) : android.view.View(context) {

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private var currentLine = 0
        private var highlightedWordCount = 0
        private var highlightedWords = emptyList<OcrWord>()
        private var recording = false

        fun update(newConfig: LayoutConfig) {
            config = newConfig
            invalidate()
        }

        fun setCurrentLine(line: Int) {
            currentLine = line.coerceAtLeast(0)
            highlightedWordCount = 0
            highlightedWords = emptyList()
            invalidate()
        }

        fun setRecording(value: Boolean) {
            recording = value
            invalidate()
        }

        fun highlight(line: Int, count: Int, words: List<OcrWord>) {
            if (line != currentLine) return
            highlightedWordCount = count
            highlightedWords = words
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val width = width.toFloat()
            val height = height.toFloat()
            val left = config.boxLeft * width
            val top = config.boxTop * height
            val right = (config.boxLeft + config.boxWidth).coerceAtMost(1f) * width
            val bottom = (config.boxTop + config.boxHeight).coerceAtMost(1f) * height

            linePaint.color = Color.YELLOW
            linePaint.strokeWidth = 5f
            canvas.drawRoundRect(left, top, right, bottom, 14f, 14f, linePaint)

            val gap = (bottom - top) / config.lineCount.coerceAtLeast(1)
            for (index in 0 until config.lineCount) {
                linePaint.strokeWidth = if (index == currentLine) 7f else 2f
                canvas.drawLine(
                    left,
                    top + gap * (index + 1),
                    right,
                    top + gap * (index + 1),
                    linePaint
                )
            }

            if (recording) {
                glowPaint.color = 0xAAFFF200.toInt()
                highlightedWords
                    .take(highlightedWordCount.coerceAtLeast(0))
                    .forEach { word ->
                        canvas.drawRect(
                            word.left.toFloat(),
                            word.top.toFloat(),
                            word.right.toFloat(),
                            word.bottom.toFloat(),
                            glowPaint
                        )
                    }
            }
        }
    }

    private class OverlayControls(
        context: Context,
        private val callbacks: Callbacks
    ) : android.view.View(context) {

        interface Callbacks {
            fun onStart()
            fun onStop()
            fun onSave()
            fun onSetLines()
        }

        var layoutParams: WindowManager.LayoutParams? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var expanded = false
        private var lastRawY = 0f
        private var moved = false

        override fun onDraw(canvas: Canvas) {
            paint.color = Color.BLACK
            canvas.drawCircle(width / 2f, 42f, 31f, paint)

            paint.color = Color.WHITE
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 18f
            canvas.drawText(
                "TG",
                width / 2f - 15f,
                48f,
                paint
            )

            if (expanded) {
                drawButton(canvas, 82f, "START")
                drawButton(canvas, 138f, "STOP")
                drawButton(canvas, 194f, "SAVE")
                drawButton(canvas, 250f, "SET LINES")
            }
        }

        private fun drawButton(canvas: Canvas, top: Float, label: String) {
            paint.color = Color.BLACK
            canvas.drawRoundRect(
                3f,
                top,
                width - 3f,
                top + 48f,
                10f,
                10f,
                paint
            )

            paint.color = Color.WHITE
            paint.textSize = 12f
            canvas.drawText(label, 12f, top + 30f, paint)
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastRawY = event.rawY
                    moved = false
                    return true
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!expanded && abs(event.rawY - lastRawY) > 4f) {
                        moved = true
                        layoutParams?.let { params ->
                            params.y = (
                                params.y + (event.rawY - lastRawY).toInt()
                            ).coerceIn(
                                0,
                                (resources.displayMetrics.heightPixels - height)
                                    .coerceAtLeast(0)
                            )
                            val prefs =
                                context.getSharedPreferences("timestamp_genius_overlay", Context.MODE_PRIVATE)
                            prefs.edit()
                                .putInt("x", params.x)
                                .putInt("y", params.y)
                                .apply()
                            val wm =
                                context.getSystemService(WINDOW_SERVICE) as WindowManager
                            wm.updateViewLayout(this, params)
                            lastRawY = event.rawY
                        }
                    }
                    return true
                }

                android.view.MotionEvent.ACTION_UP -> {
                    if (moved) return true

                    when {
                        event.y < 75f -> {
                            expanded = !expanded
                            invalidate()
                        }

                        expanded && event.y in 82f..130f -> callbacks.onStart()
                        expanded && event.y in 138f..186f -> callbacks.onStop()
                        expanded && event.y in 194f..242f -> callbacks.onSave()
                        expanded && event.y in 250f..298f -> callbacks.onSetLines()
                    }
                    expanded = false
                    invalidate()
                    return true
                }
            }

            return true
        }
    }
}
