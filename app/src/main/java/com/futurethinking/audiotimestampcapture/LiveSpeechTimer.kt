package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import com.alphacephei.vosk.Model
import com.alphacephei.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.IOException

class LiveSpeechTimer(
    private val context: Context,
    private val projection: MediaProjection,
    private val useHindiModel: Boolean,
    private val onPartial: (String, Long, List<SpokenWord>) -> Unit,
    private val onSegment: (SpokenSegment) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    @Volatile private var active=false
    private var recorder: AudioRecord?=null
    private var thread: Thread?=null
    private var model: Model?=null
    private var recognizer: Recognizer?=null
    private var startElapsed=0L

    fun start() {
        if(active) return
        active=true
        startElapsed=SystemClock.elapsedRealtime()
        thread=Thread { runCapture() }.also { it.start() }
    }

    private fun runCapture() {
        try {
            val modelDir=VoskModelFiles.ensure(context,if(useHindiModel) "hi" else "en")
            model=Model(modelDir.absolutePath)
            recognizer=Recognizer(model,16000f).apply { setWords(true) }

            val config=AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val min=AudioRecord.getMinBufferSize(
                16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT
            )
            val rec=AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build())
                .setBufferSizeInBytes(min.coerceAtLeast(8192)*2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if(rec.state!=AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Device audio capture is blocked by the source app")
            recorder=rec
            onStatus("LISTENING • device playback audio")
            rec.startRecording()
            val buffer=ByteArray(8192)
            while(active) {
                val n=rec.read(buffer,0,buffer.size)
                if(n<=0) {
                    if(n==AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("Device audio capture stopped")
                    continue
                }
                val r=recognizer ?: continue
                if(r.acceptWaveForm(buffer,n)) {
                    emitFinal(r.result)
                } else {
                    val partial=parse(r.partialResult)
                    if(partial.text.isNotBlank()) onPartial(
                        partial.text,
                        elapsed(),
                        partial.words
                    )
                }
            }
        } catch(t:Throwable) {
            if(active) onStatus("AUDIO ERROR • "+(t.message ?: "device playback capture unavailable"))
        } finally {
            cleanup()
        }
    }

    private fun emitFinal(json:String) {
        val p=parse(json)
        if(p.text.isBlank()) return
        val endRel=p.words.maxOfOrNull { (it.endSec*1000f).toLong() } ?: elapsed()
        val startRel=p.words.minOfOrNull { (it.startSec*1000f).toLong() } ?: 0L
        onSegment(
            SpokenSegment(
                startMs=startRel.coerceAtLeast(0),
                endMs=endRel.coerceAtLeast(startRel),
                text=p.text,
                words=p.words,
                confidence=p.words.map{it.confidence}.average().toFloat().takeIf{!it.isNaN()} ?: .8f
            )
        )
        onPartial(p.text,endRel,p.words)
    }

    private fun parse(json:String): Parsed {
        return runCatching {
            val root=JSONObject(json)
            val words=mutableListOf<SpokenWord>()
            val arr=root.optJSONArray("result")
            if(arr!=null) for(i in 0 until arr.length()) {
                val o=arr.optJSONObject(i) ?: continue
                words += SpokenWord(
                    o.optString("word"),
                    o.optDouble("start",0.0).toFloat(),
                    o.optDouble("end",0.0).toFloat(),
                    o.optDouble("conf",.8).toFloat().coerceIn(0f,1f)
                )
            }
            Parsed(root.optString("text",""),words)
        }.getOrElse { Parsed("",emptyList()) }
    }

    private fun elapsed():Long=(SystemClock.elapsedRealtime()-startElapsed).coerceAtLeast(0L)

    fun stop() {
        active=false
        runCatching{recorder?.stop()}
        thread?.let{runCatching{it.join(1000)}}
        cleanup()
    }

    private fun cleanup() {
        runCatching{recorder?.release()}
        recorder=null
        runCatching{recognizer?.close()}
        recognizer=null
        runCatching{model?.close()}
        model=null
        thread=null
    }

    private data class Parsed(val text:String,val words:List<SpokenWord>)
}

object VoskModelFiles {
    fun ensure(context:Context,language:String):File {
        val root=File(context.filesDir,"vosk/$language")
        if(root.exists() && File(root,"conf/model.conf").exists()) return root
        root.deleteRecursively()
        copyAssetTree(context,"models/$language",root)
        if(!File(root,"conf/model.conf").exists()) throw IOException("Bundled Vosk "+language+" model is missing")
        return root
    }

    private fun copyAssetTree(context:Context,assetPath:String,target:File) {
        target.mkdirs()
        val names=context.assets.list(assetPath) ?: emptyArray()
        for(name in names) {
            val child=assetPath+"/"+name
            val out=File(target,name)
            val nested=context.assets.list(child)
            if(nested!=null && nested.isNotEmpty()) copyAssetTree(context,child,out)
            else context.assets.open(child).use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}
