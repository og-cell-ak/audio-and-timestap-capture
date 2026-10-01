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
    private lateinit var audioStatus: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var projection: android.media.projection.MediaProjection? = null
    private var screenCapture: ScreenOcrCapture? = null
    private var audioCapture: PlaybackAudioCapture? = null
    private var speechTimer: LiveSpeechTimer? = null
    private val panels = mutableListOf<PanelReference>()
    private val speech = mutableListOf<SpokenSegment>()
    private var audioFile: File? = null
    private var capturing = false

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

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }

        root.addView(TextView(this).apply {
            text = "Audio Timestamp Studio"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(4, 0, 4, 8)
        })

        root.addView(TextView(this).apply {
            text = "Capture the narration and screen reference numbers separately. The numbers mark when each script section starts."
            textSize = 15f
            setTextColor(Color.rgb(190, 201, 224))
            setPadding(4, 0, 4, 22)
        })

        val captureCard = card()
        captureCard.addView(label("CAPTURE SESSION"))
        status = body("Ready. Start a session, then play your content normally.")
        captureCard.addView(status)
        root.addView(captureCard)

        val markerCard = card()
        markerCard.addView(label("REFERENCE NUMBERS"))
        markerStatus = body("No reference numbers detected yet.")
        markerCard.addView(markerStatus)
        root.addView(markerCard)

        val audioCard = card()
        audioCard.addView(label("OUTPUT FILES"))
        audioStatus = body("A separate WAV recording and a separate timestamped PDF will be created.")
        audioCard.addView(audioStatus)
        root.addView(audioCard)

        startButton = button("START CAPTURE", Color.rgb(141, 76, 255))
        startButton.setOnClickListener { startCaptureRequest() }
        root.addView(startButton)

        stopButton = button("STOP & BUILD PDF", Color.rgb(255, 82, 112))
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
        stopButton.setOnClickListener { stopCaptureAndBuild() }
        root.addView(stopButton)

        val info = card()
        info.addView(label("HOW IT WORKS"))
        info.addView(body("1  Screen OCR watches only for the next reference number.\n\n2  Audio is recorded as its own file.\n\n3  The detected numbers become timestamps.\n\n4  Speech between two numbers becomes that section's script.\n\n5  The final PDF contains timestamp + script only. Reference numbers are not written into the script."))
        root.addView(info)

        setContentView(scroll)
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
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

    private fun label(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(167, 126, 255))
            letterSpacing = 0.08f
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(235, 239, 250))
            setPadding(0, 10, 0, 2)
        }

    private fun button(text: String, color: Int): Button =
        Button(this).apply {
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

        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, data)

        panels.clear()
        speech.clear()
        val dir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        audioFile = File(dir, "audio-" + System.currentTimeMillis() + ".wav")

        screenCapture = ScreenOcrCapture(this, projection!!) { panel ->
            runOnUiThread {
                if (panels.none { it.number == panel.number }) {
                    panels.add(panel)
                    markerStatus.text = "Detected reference " + panel.number +
                        "\nTimestamp: " + format(panel.detectedAtMs) +
                        "\nTotal references: " + panels.size
                    status.text = "CAPTURING • Reference " + panel.number + " detected"
                }
            }
        }

        audioCapture = PlaybackAudioCapture(projection!!, audioFile!!)
        speechTimer = LiveSpeechTimer(this) { segment ->
            synchronized(speech) { speech.add(segment) }
            runOnUiThread {
                audioStatus.text = "Audio recording active\nSpeech segments detected: " + speech.size
            }
        }

        screenCapture!!.start()
        audioCapture!!.start()
        speechTimer!!.start()

        capturing = true
        startButton.isEnabled = false
        startButton.alpha = 0.45f
        stopButton.isEnabled = true
        stopButton.alpha = 1f
        status.text = "CAPTURING • Audio + reference-number detection are running"
        markerStatus.text = "Waiting for reference number 1..."
        audioStatus.text = "Recording internal playback audio..."
    }

    private fun stopCaptureAndBuild() {
        if (!capturing) return
        capturing = false

        status.text = "Finishing capture and building files..."
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
        val pdf = if (rows.isNotEmpty()) TimestampPdfWriter.write(this, rows) else null
        val savedAudio = audioFile?.takeIf { it.exists() && it.length() > 44L }
        val publicFiles = MediaFileExporter.publish(this, savedAudio, pdf)

        status.text = "CAPTURE COMPLETE\n\nReferences detected: " + orderedPanels.size +
            "\nScript sections: " + rows.size
        markerStatus.text = if (orderedPanels.isEmpty()) {
            "No reference numbers were detected."
        } else {
            "Detected: " + orderedPanels.joinToString(", ") { it.number.toString() }
        }
        audioStatus.text = buildString {
            append("Audio: ")
            append(publicFiles.audio?.absolutePath ?: savedAudio?.absolutePath ?: "not saved")
            append("\n\nPDF: ")
            append(publicFiles.pdf?.absolutePath ?: pdf?.absolutePath ?: "not created")
        }

        startButton.isEnabled = true
        startButton.alpha = 1f
        stopButton.isEnabled = false
        stopButton.alpha = 0.45f
    }

    private fun format(ms: Long): String {
        val m = ms / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return "%02d:%02d.%03d".format(m, s, x)
    }
}
