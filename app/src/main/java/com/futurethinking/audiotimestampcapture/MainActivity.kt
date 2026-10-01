package com.futurethinking.audiotimestampcapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var markerStatus: TextView
    private lateinit var outputStatus: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var freshButton: Button

    private var projection: android.media.projection.MediaProjection? = null
    private var screenCapture: ScreenOcrCapture? = null
    private var audioCapture: PlaybackAudioCapture? = null
    private var speechTimer: LiveSpeechTimer? = null
    private val panels = mutableListOf<PanelReference>()
    private val speech = mutableListOf<SpokenSegment>()
    private var audioFile: File? = null
    private var pdfFile: File? = null
    private var capturing = false
    private var generated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(8, 14, 31)
        window.navigationBarColor = Color.rgb(8, 14, 31)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(8, 14, 31))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }

        root.addView(TextView(this).apply {
            text = "Audio Timestamp Studio"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(4, 0, 4, 8)
        })
        root.addView(TextView(this).apply {
            text = "Record the audio separately and detect only the reference numbers on your screen. Those numbers become the timestamps for the script PDF."
            textSize = 15f
            setTextColor(Color.rgb(190, 201, 224))
            setPadding(4, 0, 4, 22)
        })

        val session = card()
        session.addView(label("CAPTURE SESSION"))
        status = body("Ready for a new capture.")
        session.addView(status)
        root.addView(session)

        val refs = card()
        refs.addView(label("REFERENCE NUMBERS"))
        markerStatus = body("No reference numbers detected.")
        refs.addView(markerStatus)
        root.addView(refs)

        val outputs = card()
        outputs.addView(label("OUTPUTS"))
        outputStatus = body("After stopping, press SAVE OUTPUTS. The WAV goes to Music/Audio Timestamp Studio and the PDF goes to Download/Audio Timestamp Studio.")
        outputs.addView(outputStatus)
        root.addView(outputs)

        startButton = button("START CAPTURE", Color.rgb(141, 76, 255))
        startButton.setOnClickListener { startCaptureRequest() }
        root.addView(startButton)

        stopButton = button("STOP CAPTURE", Color.rgb(255, 82, 112))
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        stopButton.setOnClickListener { stopCaptureAndBuild() }
        root.addView(stopButton)

        saveButton = button("SAVE OUTPUTS TO PHONE", Color.rgb(20, 190, 125))
        saveButton.isEnabled = false
        saveButton.alpha = 0.45f
        saveButton.setOnClickListener { saveOutputs() }
        root.addView(saveButton)

        freshButton = button("FRESH / NEW CAPTURE", Color.rgb(63, 83, 125))
        freshButton.setOnClickListener { freshCapture() }
        root.addView(freshButton)

        val info = card()
        info.addView(label("WORKFLOW"))
        info.addView(body("START CAPTURE → play your content → reference 1, 2, 3... are detected automatically → STOP CAPTURE → SAVE OUTPUTS.\n\nThe screen is used only to detect the numbers. No panels need to be displayed inside this app. Audio and timestamped PDF are saved as two separate files."))
        root.addView(info)
        setContentView(scroll)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 28f
            setColor(Color.rgb(18, 28, 53))
            setStroke(2, Color.rgb(42, 57, 91))
        }
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, 18)
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(167, 126, 255))
        letterSpacing = 0.08f
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(235, 239, 250))
        setPadding(0, 10, 0, 2)
    }

    private fun button(text: String, color: Int) = Button(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 22f
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(-1, 64).apply {
            setMargins(0, 0, 0, 16)
        }
    }

    private fun startCaptureRequest() {
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
        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
        panels.clear()
        speech.clear()
        audioFile = File(getExternalFilesDir(null), "audio-" + System.currentTimeMillis() + ".wav")
        pdfFile = null
        generated = false

        screenCapture = ScreenOcrCapture(this, projection!!) { panel ->
            runOnUiThread {
                if (panels.none { it.number == panel.number }) {
                    panels.add(panel)
                    markerStatus.text = "Detected reference " + panel.number +
                        "\nTimestamp: " + format(panel.detectedAtMs) +
                        "\nTotal detected: " + panels.size
                    status.text = "CAPTURING • Reference " + panel.number + " detected"
                }
            }
        }
        audioCapture = PlaybackAudioCapture(projection!!, audioFile!!)
        speechTimer = LiveSpeechTimer(this) { segment ->
            synchronized(speech) { speech.add(segment) }
            runOnUiThread { outputStatus.text = "Recording audio...\nSpeech segments: " + speech.size }
        }

        screenCapture!!.start()
        audioCapture!!.start()
        speechTimer!!.start()

        capturing = true
        startButton.isEnabled = false
        startButton.alpha = 0.45f
        stopButton.isEnabled = true
        stopButton.alpha = 1f
        saveButton.isEnabled = false
        saveButton.alpha = 0.45f
        status.text = "CAPTURING • Audio + reference detection active"
        markerStatus.text = "Waiting for reference number 1..."
        outputStatus.text = "Audio recording active..."
    }

    private fun stopCaptureAndBuild() {
        if (!capturing) return
        capturing = false
        status.text = "Finishing capture and building timestamp PDF..."

        screenCapture?.stop()
        speechTimer?.stop()
        audioCapture?.stop()
        projection?.stop()
        screenCapture = null
        speechTimer = null
        audioCapture = null
        projection = null

        val orderedPanels = panels.sortedBy { it.detectedAtMs }
        val orderedSpeech = synchronized(speech) { speech.sortedBy { it.startMs }.toList() }
        val rows = TimestampEngine.build(orderedPanels, orderedSpeech)
        pdfFile = if (rows.isNotEmpty()) TimestampPdfWriter.write(this, rows) else null
        val validAudio = audioFile?.takeIf { it.exists() && it.length() > 44L }

        generated = validAudio != null || pdfFile != null
        status.text = "CAPTURE COMPLETE\nReferences: " + orderedPanels.size + "\nScript sections: " + rows.size
        markerStatus.text = if (orderedPanels.isEmpty()) "No reference numbers detected." else
            "Detected: " + orderedPanels.joinToString(", ") { it.number.toString() }
        outputStatus.text = "Audio ready: " + if (validAudio != null) "YES" else "NO" +
            "\nPDF ready: " + if (pdfFile != null) "YES" else "NO" +
            "\n\nPress SAVE OUTPUTS TO PHONE."

        startButton.isEnabled = true
        startButton.alpha = 1f
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        saveButton.isEnabled = generated
        saveButton.alpha = if (generated) 1f else 0.45f
    }

    private fun saveOutputs() {
        if (!generated) return
        outputStatus.text = "Saving audio and PDF to phone storage..."
        val validAudio = audioFile?.takeIf { it.exists() && it.length() > 44L }
        val result = MediaFileExporter.publish(this, validAudio, pdfFile)
        outputStatus.text = buildString {
            append("SAVED TO PHONE\n\nAudio: ")
            append(result.audioLocation ?: "Audio could not be saved")
            append("\n\nPDF: ")
            append(result.pdfLocation ?: "PDF could not be saved")
        }
        saveButton.isEnabled = false
        saveButton.alpha = 0.65f
    }

    private fun freshCapture() {
        if (capturing) {
            screenCapture?.stop()
            speechTimer?.stop()
            audioCapture?.stop()
            projection?.stop()
        }
        capturing = false
        panels.clear()
        speech.clear()
        audioFile?.delete()
        pdfFile?.delete()
        audioFile = null
        pdfFile = null
        generated = false
        startButton.isEnabled = true
        startButton.alpha = 1f
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        saveButton.isEnabled = false
        saveButton.alpha = 0.45f
        status.text = "Fresh capture ready."
        markerStatus.text = "No reference numbers detected."
        outputStatus.text = "Nothing saved. Press START CAPTURE to begin a new session."
    }

    private fun format(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
