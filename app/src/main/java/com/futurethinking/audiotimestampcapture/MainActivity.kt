package com.futurethinking.audiotimestampcapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var markers: TextView
    private lateinit var scriptStatus: TextView
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var downloadButton: TextView
    private lateinit var freshButton: TextView

    private var projection: android.media.projection.MediaProjection? = null
    private var screenCapture: ScreenOcrCapture? = null
    private var speechTimer: LiveSpeechTimer? = null
    private val panels = mutableListOf<PanelReference>()
    private val speech = mutableListOf<SpokenSegment>()
    private var pdfUri: android.net.Uri? = null
    private var capturing = false
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(8, 12, 28)
        window.navigationBarColor = Color.rgb(8, 12, 28)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 26, 22, 30)
            setBackgroundColor(Color.rgb(8, 12, 28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(title("Audio Timestamp Studio", 30f, Color.WHITE))
        root.addView(body("Reads the reference numbers from the screen and listens to the narration. Each new number becomes the timestamp for the next script section.", 15f))

        val live = card()
        live.addView(label("LIVE CAPTURE"))
        status = body("Ready. Press START CAPTURE, then switch to the content you want to analyse.", 17f)
        live.addView(status)
        root.addView(live)

        val markerCard = card()
        markerCard.addView(label("SCREEN REFERENCES"))
        markers = body("Waiting for reference 1.", 17f)
        markerCard.addView(markers)
        root.addView(markerCard)

        val scriptCard = card()
        scriptCard.addView(label("TIMESTAMP SCRIPT"))
        scriptStatus = body("No script captured yet.", 17f)
        scriptCard.addView(scriptStatus)
        root.addView(scriptCard)

        startButton = actionButton("START CAPTURE", Color.rgb(142, 78, 255))
        startButton.setOnClickListener { requestCapture() }
        root.addView(startButton)

        stopButton = actionButton("STOP & BUILD PDF", Color.rgb(247, 73, 105))
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        stopButton.setOnClickListener { stopAndBuild() }
        root.addView(stopButton)

        downloadButton = actionButton("DOWNLOAD TIMESTAMP PDF", Color.rgb(22, 190, 126))
        downloadButton.isEnabled = false
        downloadButton.alpha = 0.45f
        downloadButton.setOnClickListener { downloadPdf() }
        root.addView(downloadButton)

        freshButton = actionButton("FRESH / NEW CAPTURE", Color.rgb(55, 73, 112))
        freshButton.setOnClickListener { freshCapture() }
        root.addView(freshButton)

        val how = card()
        how.addView(label("HOW IT WORKS"))
        how.addView(body(
            "1. Press START CAPTURE.\n" +
            "2. Give screen-capture permission.\n" +
            "3. Switch to your source content.\n" +
            "4. The app watches only for reference numbers 1, 2, 3...\n" +
            "5. The narration is listened to live but is NOT saved as an audio file.\n" +
            "6. When the next number appears, its capture time becomes the next timestamp.\n" +
            "7. Press STOP & BUILD PDF.\n" +
            "8. Press DOWNLOAD TIMESTAMP PDF to put the PDF in Downloads.",
            15f
        ))
        root.addView(how)

        setContentView(scroll)
    }

    private fun title(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        setPadding(2, 0, 2, 8)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(177, 133, 255))
        letterSpacing = 0.08f
    }

    private fun body(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(235, 239, 250))
        setPadding(0, 10, 0, 4)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 18, 20, 18)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 28f
            setColor(Color.rgb(18, 27, 50))
            setStroke(2, Color.rgb(40, 55, 88))
        }
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 16)
        }
    }

    private fun actionButton(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(18, 18, 18, 18)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24f
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(-1, 68).apply {
            setMargins(0, 0, 0, 14)
        }
    }

    private fun requestCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 900)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 7001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7001 || resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission was not granted."
            return
        }

        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)

        panels.clear()
        speech.clear()
        pdfUri = null
        building = false

        screenCapture = ScreenOcrCapture(this, projection!!) { panel ->
            runOnUiThread {
                if (panels.none { it.number == panel.number }) {
                    panels.add(panel)
                    val detected = panels.sortedBy { it.detectedAtMs }
                    markers.text = detected.joinToString("\n") {
                        "Reference " + it.number + "  •  " + format(it.detectedAtMs)
                    }
                    status.text = "CAPTURING • reference " + panel.number + " detected"
                }
            }
        }

        speechTimer = LiveSpeechTimer(this) { segment ->
            synchronized(speech) { speech.add(segment) }
            runOnUiThread {
                scriptStatus.text = "Listening to narration...\nSpeech segments received: " + speech.size
            }
        }

        screenCapture!!.start()
        speechTimer!!.start()

        capturing = true
        startButton.isEnabled = false
        startButton.alpha = 0.45f
        stopButton.isEnabled = true
        stopButton.alpha = 1f
        downloadButton.isEnabled = false
        downloadButton.alpha = 0.45f
        markers.text = "Waiting for reference 1..."
        scriptStatus.text = "Listening to narration..."
        status.text = "CAPTURING • screen + audio listening active"
    }

    private fun stopAndBuild() {
        if (!capturing || building) return
        building = true
        capturing = false
        status.text = "Stopping capture and building timestamp PDF..."

        screenCapture?.stop()
        speechTimer?.stop()
        projection?.stop()
        screenCapture = null
        speechTimer = null
        projection = null

        val orderedPanels = panels.sortedBy { it.detectedAtMs }
        val orderedSpeech = synchronized(speech) { speech.sortedBy { it.startMs }.toList() }
        val rows = TimestampEngine.build(orderedPanels, orderedSpeech)

        pdfUri = if (rows.isNotEmpty()) {
            TimestampPdfWriter.writeToDownloads(this, rows)
        } else null

        if (pdfUri != null) {
            status.text = "CAPTURE COMPLETE • timestamp PDF ready"
            scriptStatus.text = rows.size.toString() + " timestamped script sections created."
            downloadButton.isEnabled = true
            downloadButton.alpha = 1f
        } else {
            status.text = "No timestamped script was created."
            scriptStatus.text = if (orderedPanels.isEmpty()) {
                "No reference numbers were detected. Keep the numbers visible long enough for OCR to read them."
            } else {
                "References were detected, but no speech text was received."
            }
        }

        startButton.isEnabled = true
        startButton.alpha = 1f
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        building = false
    }

    private fun downloadPdf() {
        val uri = pdfUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "PDF saved in Downloads/Audio Timestamp Studio", Toast.LENGTH_LONG).show()
        }
    }

    private fun freshCapture() {
        if (capturing) {
            screenCapture?.stop()
            speechTimer?.stop()
            projection?.stop()
        }
        capturing = false
        building = false
        panels.clear()
        speech.clear()
        pdfUri = null
        startButton.isEnabled = true
        startButton.alpha = 1f
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        downloadButton.isEnabled = false
        downloadButton.alpha = 0.45f
        status.text = "Fresh capture ready."
        markers.text = "Waiting for reference 1."
        scriptStatus.text = "No script captured yet."
    }

    private fun format(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
