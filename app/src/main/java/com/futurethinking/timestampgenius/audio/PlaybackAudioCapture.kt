package com.futurethinking.timestampgenius.audio
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaProjection
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
class PlaybackAudioCapture(private val context:Context,private val projection:MediaProjection,private val language:String,private val onText:(String,Boolean)->Unit,private val onError:(String)->Unit){private val running=AtomicBoolean(false);private var thread:Thread?=null;private var engine:VoskSpeechEngine?=null
fun start():Boolean{if(Build.VERSION.SDK_INT<29){onError("Device audio capture needs Android 10 or newer.");return false};if(running.get())return true;val e=VoskSpeechEngine(context);if(!e.start(language,onText)){onError("Offline speech model could not be loaded.");return false};val cfg=AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build();val fmt=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);val size=(min*4).coerceAtLeast(16000);val rec=try{AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(size).setAudioPlaybackCaptureConfig(cfg).build()}catch(_:Throwable){e.stop();onError("This source app or device rejected playback capture.");return false};if(rec.state!=AudioRecord.STATE_INITIALIZED){rec.release();e.stop();onError("Playback audio capture could not be initialized.");return false};engine=e;running.set(true);thread=Thread{val buf=ByteArray(16000);try{rec.startRecording();while(running.get()){val n=rec.read(buf,0,buf.size,AudioRecord.READ_BLOCKING);if(n>0)e.acceptPcm16(buf,n)else if(n<0){onError("Playback audio stream stopped.");break}}}catch(t:Throwable){if(running.get())onError("Playback audio capture failed: "+(t.message?:"unknown error"))}finally{runCatching{rec.stop()};rec.release();e.stop();engine=null;running.set(false)}}.apply{isDaemon=true;name="TimestampGenius-Audio"};thread?.start();return true}
fun stop(){running.set(false);runCatching{thread?.join(700)};thread=null;engine?.stop();engine=null}
}
