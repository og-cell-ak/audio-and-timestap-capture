package com.timestampgenius

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingStart = false

    private var status by mutableStateOf("")
    private var preview by mutableStateOf<List<ScriptLine>>(emptyList())
    private var lastPdf by mutableStateOf<Pair<String, String>?>(null)
    private var showNewSessionDialog by mutableStateOf(false)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, TimestampForegroundService::class.java).apply {
                        action = TimestampForegroundService.ACTION_START_SESSION
                        putExtra(TimestampForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(TimestampForegroundService.EXTRA_RESULT_DATA, result.data)
                    }
                )
            } else {
                status = "Screen capture permission was not granted."
            }
        }

    private val pdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handlePdf(uri)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (!pendingStart) return@registerForActivityResult
            pendingStart = false

            if (result[Manifest.permission.RECORD_AUDIO] == false) {
                status = "Audio capture permission was denied. Timestamp Genius needs it for device audio recognition."
                return@registerForActivityResult
            }

            continueStartFlow()
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status = intent?.getStringExtra(AppState.EXTRA_MESSAGE).orEmpty()
            preview = store.getLines()
            lastPdf = store.getLastPdf()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        preview = store.getLines()
        lastPdf = store.getLastPdf()

        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(AppState.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = Color.White,
                    surface = Color.White,
                    onBackground = Color.Black,
                    onSurface = Color.Black,
                    primary = Color.Black,
                    onPrimary = Color.White
                )
            ) {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    @Composable
    private fun MainScreen() {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Timestamp Genius",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.Black
                )

                Text(
                    "Device audio + screen script tracking. Processing stays on the device after the required Android capture permissions are granted.",
                    color = Color.Black
                )

                Button(
                    onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("UPLOAD PDF") }

                Button(
                    onClick = { startFlow() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("START") }

                Button(
                    onClick = { showLastPdfMenu() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("LAST PDF RECORDED") }

                Button(
                    onClick = { showNewSessionDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("NEW SESSION") }

                if (status.isNotBlank()) {
                    Text(status, color = Color.Black)
                }

                if (preview.isNotEmpty()) {
                    Text(
                        "Script preview: ${preview.size} yellow-line-separated lines",
                        color = Color.Black
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(preview) { line ->
                            Text(
                                "${line.index + 1}. ${line.text}",
                                color = Color.Black,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (showNewSessionDialog) {
            ComposeAlertDialog(
                onDismissRequest = { showNewSessionDialog = false },
                title = { Text("New session") },
                text = {
                    Text(
                        "This clears the active session, timestamps, cache reference and uploaded PDF reference. Saved PDF files already in phone storage are not deleted."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (store.isRunning()) {
                                ContextCompat.startForegroundService(
                                    this@MainActivity,
                                    Intent(this@MainActivity, TimestampForegroundService::class.java).apply {
                                        action = TimestampForegroundService.ACTION_STOP_SESSION
                                    }
                                )
                            }
                            store.clearSession()
                            preview = emptyList()
                            status = "New session started."
                            showNewSessionDialog = false
                        }
                    ) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { showNewSessionDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    private fun handlePdf(uri: Uri) {
        status = "Reading PDF and detecting yellow separators..."
        uiScope.launch {
            val engine = OcrEngine()
            val result = PdfScriptReader(this@MainActivity, engine).extract(uri)
            engine.close()

            result
                .onSuccess { lines ->
                    store.setPdfReference("uploaded.pdf", lines)
                    preview = store.getLines()
                    status = "PDF loaded. Detected ${lines.size} script lines."
                }
                .onFailure {
                    status = it.message ?: "The PDF could not be processed."
                }
        }
    }

    private fun startFlow() {
        pendingStart = true

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:\$packageName")
                )
            )
            pendingStart = false
            status = "Allow display over other apps, then press START again."
            return
        }

        val needs = mutableListOf<String>()
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needs += Manifest.permission.RECORD_AUDIO
        }

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needs += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needs.isNotEmpty()) {
            permissionLauncher.launch(needs.toTypedArray())
        } else {
            pendingStart = false
            continueStartFlow()
        }
    }

    private fun continueStartFlow() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun showLastPdfMenu() {
        val last = store.getLastPdf()
        if (last == null) {
            status = "No PDF recorded yet"
            return
        }

        AlertDialog.Builder(this)
            .setTitle(last.second)
            .setItems(arrayOf("Open", "Share")) { _, which ->
                val uri = Uri.parse(last.first)
                runCatching {
                    if (which == 0) {
                        startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    } else {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share PDF"
                            )
                        )
                    }
                }.onFailure {
                    status = "No compatible PDF viewer or sharing app was found."
                }
            }
            .show()
    }
}
