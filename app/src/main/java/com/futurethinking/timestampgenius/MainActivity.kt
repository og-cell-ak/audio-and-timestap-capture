package com.futurethinking.timestampgenius

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.futurethinking.timestampgenius.pdf.PdfScriptReader
import com.futurethinking.timestampgenius.service.TimestampOverlayService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private var preparing = false
    private var permissionStage = 0

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lifecycleScope.launch {
            runCatching {
                PdfScriptReader.extractLines(this@MainActivity, uri)
            }.onSuccess { lines ->
                if (lines.isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "The PDF contains no script segments.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    store.setScript(uri, lines)
                    Toast.makeText(
                        this@MainActivity,
                        "Detected " + lines.size + " script lines.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "Could not read the PDF.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        continuePreparation()
    }

    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            preparing = false
            Toast.makeText(
                this,
                "Screen and device audio capture permission was not granted.",
                Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }

        val serviceIntent = Intent(
            this,
            TimestampOverlayService::class.java
        ).apply {
            action = TimestampOverlayService.ACTION_PREPARE
            putExtra(
                TimestampOverlayService.EXTRA_PROJECTION_DATA,
                data
            )
        }

        androidx.core.content.ContextCompat.startForegroundService(
            this,
            serviceIntent
        )

        preparing = false
        permissionStage = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore.get(this)

        setContent {
            val lines by store.scriptLines.collectAsState()
            val lastPdf by store.lastPdf.collectAsState()
            var showResetDialog by remember { mutableStateOf(false) }

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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "Timestamp Genius",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.Black
                    )
                    Text(
                        "Offline script line timestamping",
                        color = Color.Black
                    )

                    MainButton("UPLOAD PDF") {
                        pdfPicker.launch(arrayOf("application/pdf"))
                    }

                    MainButton("START") {
                        beginPreparation()
                    }

                    MainButton("LAST PDF RECORDED") {
                        val uri = lastPdf?.let(Uri::parse)
                        if (uri == null) {
                            Toast.makeText(
                                this@MainActivity,
                                "No PDF recorded yet",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            runCatching {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, uri).apply {
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                )
                            }.onFailure {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No PDF viewer is available.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    MainButton("NEW SESSION") {
                        showResetDialog = true
                    }

                    Text(
                        if (lines.isEmpty()) {
                            "No PDF loaded. Screen reading mode is ready."
                        } else {
                            "Loaded script: " + lines.size + " yellow line segments"
                        },
                        color = Color.Black
                    )

                    if (lines.isNotEmpty()) {
                        Text(
                            "Preview",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Black
                        )

                        lines.take(12).forEachIndexed { index, text ->
                            Text(
                                (index + 1).toString() + ". " + text,
                                color = Color.Black
                            )
                        }

                        if (lines.size > 12) {
                            Text(
                                "... " + (lines.size - 12) + " more lines",
                                color = Color.Black
                            )
                        }
                    }
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("New session") },
                        text = {
                            Text(
                                "This clears the current script, timestamps and PDF reference. " +
                                    "Saved PDF files already on the phone are not deleted."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    store.clearCurrentSession()
                                    stopService(
                                        Intent(
                                            this@MainActivity,
                                            TimestampOverlayService::class.java
                                        )
                                    )
                                    showResetDialog = false
                                }
                            ) {
                                Text("Continue")
                            }
                        },
                        dismissButton = {
                            Button(onClick = { showResetDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MainButton(
        label: String,
        onClick: () -> Unit
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White
            )
        ) {
            Text(label)
        }
    }

    private fun beginPreparation() {
        preparing = true
        permissionStage = 0
        continuePreparation()
    }

    private fun continuePreparation() {
        if (!preparing) return

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + packageName)
                )
            )
            return
        }

        val needed = buildList {
            if (
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }

            if (
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty() && permissionStage == 0) {
            permissionStage = 1
            permissionRequest.launch(needed.toTypedArray())
            return
        }

        permissionStage = 2
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCapture.launch(manager.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        if (preparing && permissionStage == 0) {
            continuePreparation()
        }
    }
}
