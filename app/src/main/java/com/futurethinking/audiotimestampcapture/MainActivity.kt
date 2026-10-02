package com.futurethinking.audiotimestampcapture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.registerForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : androidx.activity.ComponentActivity() {
    private var statusState=mutableStateOf("READY")
    private var scriptCountState=mutableStateOf(0)
    private var lastPdfState=mutableStateOf<Uri?>(null)
    private var newSessionDialog=mutableStateOf(false)

    private val pdfPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null) return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        statusState.value="READING PDF • yellow separators are being detected"
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lines=PdfScriptParser.parse(this@MainActivity,uri)
                SessionStore.saveScript(this@MainActivity,lines)
                SessionStore.savePdfInput(this@MainActivity,uri)
                withContext(Dispatchers.Main) {
                    scriptCountState.value=lines.size
                    statusState.value="PDF READY • "+lines.size+" script lines detected"
                }
            } catch(t:Throwable) {
                withContext(Dispatchers.Main) {
                    statusState.value="PDF ERROR • "+(t.message ?: "invalid PDF")
                    Toast.makeText(this@MainActivity,t.message ?: "Invalid PDF",Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private val projectionLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode!=Activity.RESULT_OK || result.data==null) {
            statusState.value="Screen capture permission cancelled"
            return@registerForActivityResult
        }
        val service=Intent(this,CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE,result.resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA,result.data)
        }
        ContextCompat.startForegroundService(this,service)
        statusState.value="FLOATING CONTROL READY"
    }

    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted || Build.VERSION.SDK_INT<33) launchProjection()
        else statusState.value="Notification permission denied • foreground service cannot be fully notified"
    }

    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            when(intent?.action){
                CaptureService.ACTION_STATUS -> statusState.value=intent.getStringExtra(CaptureService.EXTRA_MESSAGE) ?: "Working..."
                CaptureService.ACTION_PDF_READY -> {
                    lastPdfState.value=intent.getStringExtra(CaptureService.EXTRA_URI)?.let(Uri::parse) ?: SessionStore.loadLastPdf(this@MainActivity)
                    statusState.value="PDF SAVED • Downloads/ScriptTimestamper"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        lastPdfState.value=SessionStore.loadLastPdf(this)
        scriptCountState.value=SessionStore.loadScript(this).size
        setContent {
            val status=statusState.value
            val count=scriptCountState.value
            val last=lastPdfState.value
            val showNew=newSessionDialog.value
            Surface(color=androidx.compose.ui.graphics.Color.White,modifier=Modifier.fillMaxSize()){
                Column(modifier=Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    Text("TIMESTAMP GENIUS",style=MaterialTheme.typography.headlineMedium,color=androidx.compose.ui.graphics.Color.Black)
                    Text("Device audio + screen script timing. Everything processed on device.",color=androidx.compose.ui.graphics.Color.Black)
                    Text(if(count>0) "Script lines: "+count else "No PDF uploaded • screen mode",color=androidx.compose.ui.graphics.Color.DarkGray)
                    Text(status,color=androidx.compose.ui.graphics.Color.Black)
                    Button(modifier=Modifier.fillMaxWidth(),onClick={pdfPicker.launch(arrayOf("application/pdf"))}){Text("UPLOAD PDF")}
                    Button(modifier=Modifier.fillMaxWidth(),onClick={startFlow()}){Text("START")}
                    Button(modifier=Modifier.fillMaxWidth(),onClick={openLastPdf()}){Text("LAST PDF RECORDED")}
                    Button(modifier=Modifier.fillMaxWidth(),onClick={newSessionDialog.value=true}){Text("NEW SESSION")}
                }
            }
            if(showNew){
                AlertDialog(
                    onDismissRequest={newSessionDialog.value=false},
                    title={Text("Clear old session?")},
                    text={Text("This clears the active script, timestamps and PDF reference. Saved PDFs in storage are not deleted.")},
                    confirmButton={Button(onClick={
                        CaptureService.stopAndClear(this@MainActivity)
                        SessionStore.clearSession(this@MainActivity)
                        scriptCountState.value=0
                        lastPdfState.value=null
                        statusState.value="NEW SESSION READY"
                        newSessionDialog.value=false
                    }){Text("CONTINUE")}},
                    dismissButton={Button(onClick={newSessionDialog.value=false}){Text("CANCEL")}}
                )
            }
        }
    }

    private fun startFlow(){
        if(!Settings.canDrawOverlays(this)){
            statusState.value="ALLOW DISPLAY OVER OTHER APPS, THEN PRESS START AGAIN"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            return
        }
        if(Build.VERSION.SDK_INT>=33){
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else launchProjection()
    }

    private fun launchProjection(){
        val manager=getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openLastPdf(){
        val uri=lastPdfState.value ?: SessionStore.loadLastPdf(this)
        if(uri==null){Toast.makeText(this,"No PDF recorded yet",Toast.LENGTH_SHORT).show();return}
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(uri,"application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { Toast.makeText(this,"PDF saved in Downloads/ScriptTimestamper",Toast.LENGTH_LONG).show() }
    }

    override fun onStart(){
        super.onStart()
        val f=IntentFilter().apply{addAction(CaptureService.ACTION_STATUS);addAction(CaptureService.ACTION_PDF_READY)}
        if(Build.VERSION.SDK_INT>=33){
            registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED)
        }else{
            @Suppress("DEPRECATION")
            registerReceiver(receiver,f)
        }
    }

    override fun onStop(){
        runCatching{unregisterReceiver(receiver)}
        super.onStop()
    }

    override fun onDestroy(){
        super.onDestroy()
    }
}
