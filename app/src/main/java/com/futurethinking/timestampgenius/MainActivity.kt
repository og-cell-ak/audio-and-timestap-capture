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

class MainActivity:ComponentActivity(){
 private lateinit var store:SessionStore
 private var preparing=false
 private var stage=0
 private val pdfPicker=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)};lifecycleScope.launch{runCatching{PdfScriptReader.extractLines(this@MainActivity,uri)}.onSuccess{store.setScript(uri,it)}.onFailure{Toast.makeText(this@MainActivity,it.message?:"Could not read PDF",Toast.LENGTH_LONG).show()}}}}
 private val perms=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){continuePrepare()}
 private val projection=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){r->if(r.resultCode==Activity.RESULT_OK&&r.data!=null){val i=Intent(this,TimestampOverlayService::class.java).apply{action=TimestampOverlayService.ACTION_PREPARE;putExtra(TimestampOverlayService.EXTRA_PROJECTION_DATA,r.data)};androidx.core.content.ContextCompat.startForegroundService(this,i);preparing=false;stage=0}else{preparing=false;Toast.makeText(this,"Screen and device audio permission was not granted.",Toast.LENGTH_LONG).show()}}
 override fun onCreate(b:Bundle?){super.onCreate(b);store=SessionStore.get(this);setContent{val lines by store.scriptLines.collectAsState();val last by store.lastPdf.collectAsState();var confirm by remember{mutableStateOf(false)};MaterialTheme(colorScheme=lightColorScheme(background=Color.White,surface=Color.White,onBackground=Color.Black,onSurface=Color.Black,primary=Color.Black)){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Timestamp Genius",style=MaterialTheme.typography.headlineMedium,color=Color.Black);Text("Offline script line timestamping",color=Color.Black);MainButton("UPLOAD PDF"){pdfPicker.launch(arrayOf("application/pdf"))};MainButton("START"){beginPrepare()};MainButton("LAST PDF RECORDED"){val u=last?.let(Uri::parse);if(u==null)Toast.makeText(this@MainActivity,"No PDF recorded yet",Toast.LENGTH_SHORT).show()else runCatching{startActivity(Intent(Intent.ACTION_VIEW,u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}.onFailure{Toast.makeText(this@MainActivity,"No PDF viewer is available.",Toast.LENGTH_LONG).show()}};MainButton("NEW SESSION"){confirm=true};Text(if(lines.isEmpty())"No PDF loaded. Screen reading mode is ready." else "Loaded script: "+lines.size+" yellow-line segments",color=Color.Black);if(lines.isNotEmpty()){Text("Preview",style=MaterialTheme.typography.titleMedium,color=Color.Black);lines.take(8).forEachIndexed{i,t->Text((i+1).toString()+". "+t,color=Color.Black))}}};if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("New session")},text={Text("This clears the current script, timestamps and PDF reference. Saved PDF files are not deleted.")},confirmButton={Button(onClick={store.clearCurrentSession();confirm=false;stopService(Intent(this,TimestampOverlayService::class.java))}){Text("Continue")}},dismissButton={Button(onClick={confirm=false}){Text("Cancel")}})}}}}
 @Composable private fun MainButton(label:String,onClick:()->Unit){Button(onClick,onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Color.Black,contentColor=Color.White)){Text(label)}}
 private fun beginPrepare(){preparing=true;stage=0;continuePrepare()}
 private fun continuePrepare(){if(!preparing)return;if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+packageName)));return};val need=ArrayList<String>();if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)need+=Manifest.permission.RECORD_AUDIO;if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)need+=Manifest.permission.POST_NOTIFICATIONS;if(need.isNotEmpty()&&stage==0){stage=1;perms.launch(need.toTypedArray());return};stage=2;projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())}
 override fun onResume(){super.onResume();if(preparing)continuePrepare()}
}
