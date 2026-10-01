package com.futurethinking.audiotimestampcapture

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var lastPdf: Uri? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CaptureService.ACTION_STATUS -> {
                    status.text = intent.getStringExtra(CaptureService.EXTRA_MESSAGE) ?: "Working..."
                }
                CaptureService.ACTION_PDF_READY -> {
                    lastPdf = intent.getStringExtra(CaptureService.EXTRA_URI")?.let(Uri::parse)
                    status.text = "PDF READY • saved in Downloads/Audio Timestamp Studio"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CaptureService.ACTION_STATUS)
            addAction(CaptureService.ACTION_PDF_READY)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(8, 12, 28)
        window.navigationBarColor = Color.rgb(8, 12, 28)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 30)
            setBackgroundColor(Color.rgb(8, 12, 28))
        }

        root.addView(TextView(this).apply {
            text = "Audio Timestamp Studio"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Define a box once. The app reads the equal-spaced lines inside it and listens to the narration. It only creates timestamps when screen text and spoken text can be matched."
            textSize = 16f
            setTextColor(Color.rgb(225, 231, 244))
            setPadding(0, 12, 0, 22)
        })

        val info = TextView(this).apply {
            text = "WORKFLOW\n1. Start screen + audio capture\n2. Use the yellow floating SET LINES button\n3. Draw the box and choose line count\n4. Play your narration\n5. Stop and build the timestamp PDF\n\nNo audio file is saved."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.rgb(18, 27, 50))
                setStroke(2, Color.rgb(47, 63, 98))
            }
        }
        root.addView(info)

        status = TextView(this).apply {
            text = "READY"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 193, 7))
            setPadding(0, 22, 0, 18)
        }
        root.addView(status)

        root.addView(button("START SCREEN + AUDIO", Color.rgb(108, 77, 255)) { startCaptureFlow() })
        root.addView(button("STOP & BUILD TIMESTAMP PDF", Color.rgb(229, 57, 53)) {
            sendServiceAction(CaptureService.ACTION_STOP)
        })
        root.addView(button("OPEN LAST TIMESTAMP PDF", Color.rgb(23, 181, 119)) { openPdf() })
        root.addView(button("FRESH / NEW SESSION", Color.rgb(55, 73, 112)) {
            stopService(Intent(this, CaptureService::class.java))
            lastPdf = null
            status.text = "FRESH SESSION READY"
        })

        setContentView(root)
    }

    private fun button(text: String, color: Int, click: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(16, 18, 16, 18)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 24f
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(-1, 68).apply { setMargins(0, 0, 0, 14) }
        setOnClickListener { click() }
    }

    private fun startCaptureFlow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            status.text = "ENABLE 'DISPLAY OVER OTHER APPS', THEN PRESS START AGAIN"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 7001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCaptureFlow()
        } else if (requestCode == 41) {
            status.text = "Microphone/audio permission is required."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7001) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission cancelled."
            return
        }

        val service = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, service)
        status.text = "STARTING • floating controls will appear"
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, CaptureService::class.java).apply { this.action = action }
        startService(intent)
    }

    private fun openPdf() {
        val uri = lastPdf ?: run {
            Toast.makeText(this, "No timestamp PDF is ready yet.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Throwable) {
            Toast.makeText(this, "PDF is in Downloads/Audio Timestamp Studio", Toast.LENGTH_LONG).show()
        }
    }
}
