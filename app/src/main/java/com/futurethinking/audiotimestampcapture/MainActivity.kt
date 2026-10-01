package com.futurethinking.audiotimestampcapture

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        status = TextView(this).apply {
            text = "Audio + Screen Timestamp Capture\n\nSTART capture, let the app observe numbered panels and audio, then STOP to build the timestamped PDF."
            textSize = 18f
        }

        val start = Button(this).apply {
            text = "START CAPTURE"
            setOnClickListener { requestProjection() }
        }

        val stop = Button(this).apply {
            text = "STOP / BUILD PDF"
            setOnClickListener {
                status.text = "Capture stopped. Final processing will transcribe audio, align spoken segments with panel references, remove reference numbers, and create the PDF."
            }
        }

        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun requestProjection() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 7001)
    }
}
