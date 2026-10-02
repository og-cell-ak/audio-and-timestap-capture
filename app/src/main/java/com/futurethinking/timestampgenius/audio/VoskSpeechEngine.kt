package com.futurethinking.timestampgenius.audio
import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
class VoskSpeechEngine(private val context:Context){
    // Models are copied from APK assets into private storage once, then reused offline.private var model:Model?=null;private var recognizer:Recognizer?=null;private var cb:((String,Boolean)->Unit)?=null
fun start(language:String,onText:(String,Boolean)->Unit):Boolean{stop();val folder=if(language=="hi")"vosk-model-small-hi-0.22" else "vosk-model-small-en-us-0.15";return try{val dir=File(context.filesDir,"models/"+folder);copyIfNeeded("models/"+folder,dir);val m=Model(dir.absolutePath);model=m;recognizer=Recognizer(m,16000f);cb=onText;true}catch(_:Throwable){stop();false}}
fun acceptPcm16(pcm:ByteArray,len:Int){val r=recognizer?:return;try{if(r.acceptWaveForm(pcm,len))cb?.invoke(JSONObject(r.result).optString("text",""),true)else{val p=JSONObject(r.partialResult).optString("partial","");if(p.isNotBlank())cb?.invoke(p,false)}}catch(_:Throwable){}}
fun stop(){runCatching{recognizer?.close()};runCatching{model?.close()};recognizer=null;model=null;cb=null}
private fun copyIfNeeded(asset:String,dest:File){if(dest.exists()&&File(dest,"am/final.mdl").exists())return;dest.deleteRecursively();dest.mkdirs();copy(asset,dest)}
private fun copy(asset:String,dest:File){val kids=context.assets.list(asset)?:emptyArray();if(kids.isEmpty()){context.assets.open(asset).use{input->dest.outputStream().use{input.copyTo(it)}};return};dest.mkdirs();kids.forEach{copy(asset+"/"+it,File(dest,it))}}
}
