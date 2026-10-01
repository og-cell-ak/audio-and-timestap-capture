package com.futurethinking.audiotimestampcapture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var projection: android.media.projection.MediaProjection? = null
    private var screenCapture: ScreenOcrCapture? = null
    private var audioCapture: PlaybackAudioCapture? = null
    private var speechTimer: LiveSpeechTimer? = null
    private val panels = mutableListOf<PanelReference>()
    private val speech = mutableListOf<SpokenSegment>()
    private var captureStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 24, 32, 24) }
        status = TextView(this).apply {
            text = "Audio + Screen Timestamp Capture\n\nThe app watches the screen for numbered panel references and listens to spoken audio. Panel references are removed from the final PDF."
            textSize = 17f
        }
        val start = Button(this).apply { text = "START CAPTURE"; setOnClickListener { startCaptureRequest() } }
        val stop = Button(this).apply { text = "STOP + BUILD PDF"; setOnClickListener { stopCaptureAndBuild() } }
        root.addView(status); root.addView(start); root.addView(stop); setContentView(root)
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
            status.text = "Screen capture permission was not granted."; return
        }
        projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(resultCode, data)
        panels.clear(); speech.clear(); captureStartedAt = System.currentTimeMillis()
        val dir = File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val wav = File(dir, "capture-$captureStartedAt.wav")

        screenCapture = ScreenOcrCapture(this, projection!!) { panel ->
            runOnUiThread {
                if (panels.none { it.number == panel.number }) {
                    panels.add(panel)
                    status.text = "Captured panel reference ${panel.number}. Total panels: ${panels.size}"
                }
            }
        }
        screenCapture!!.start()
        audioCapture = PlaybackAudioCapture(projection!!, wav)
        audioCapture!!.start()
        speechTimer = LiveSpeechTimer(this) { segment ->
            synchronized(speech) { speech.add(segment) }
            runOnUiThread { status.text = "Listening... Panels: ${panels.size}, speech segments: ${speech.size}" }
        }
        speechTimer!!.start()
        status.text = "CAPTURING... Show the numbered panels and play the narration."
    }

    private fun stopCaptureAndBuild() {
        screenCapture?.stop(); audioCapture?.stop(); speechTimer?.stop(); projection?.stop()
        screenCapture = null; audioCapture = null; speechTimer = null; projection = null
        val orderedPanels = panels.sortedWith(compareBy<PanelReference> { it.top }.thenBy { it.left })
        val orderedSpeech = speech.sortedBy { it.startMs }
        val rows = TimestampEngine.build(orderedPanels, orderedSpeech)
        if (rows.isEmpty()) { status.text = "No usable panel/timestamp pairs were detected. Nothing was guessed."; return }
        val pdf = TimestampPdfWriter.write(this, rows)
        status.text = "DONE\n\nDetected panels: ${orderedPanels.size}\nTimestamped script rows: ${rows.size}\n\nPDF created:\n${pdf.absolutePath}"
    }
}
