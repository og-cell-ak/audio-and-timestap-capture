package com.futurethinking.timestampgenius
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class SessionStore private constructor(context:Context){
 private val prefs=context.getSharedPreferences("timestamp_genius",Context.MODE_PRIVATE)
 private val _script=MutableStateFlow(loadLines());val scriptLines:StateFlow<List<String>>=_script
 private val _timestamps=MutableStateFlow(loadTimes());val timestamps:StateFlow<List<TimestampLine>>=_timestamps
 private val _last=MutableStateFlow(prefs.getString("last_pdf",null));val lastPdf:StateFlow<String?>=_last
 private val _layout=MutableStateFlow(loadLayout());val layout:StateFlow<LayoutConfig>=_layout
 val layoutReady:Boolean get()=prefs.getBoolean("layout_ready",false)
 fun setScript(uri:Uri?,lines:List<String>){prefs.edit().putString("script_uri",uri?.toString()).putString("script_lines",JSONArray(lines).toString()).apply();_script.value=lines;refreshTimestampLines()}
 fun replaceScript(lines:List<String>)=setScript(null,lines)
 fun clearCurrentSession(){prefs.edit().remove("script_uri").remove("script_lines").remove("timestamps").remove("last_pdf").apply();_script.value=emptyList();_timestamps.value=emptyList();_last.value=null}
 fun recordTimestamp(index:Int,elapsed:Long){val base=_script.value;val cur=_timestamps.value.toMutableList();while(cur.size<base.size)cur+=TimestampLine(cur.size,base[cur.size],null);if(index in cur.indices&&cur[index].millis==null){cur[index]=cur[index].copy(millis=elapsed);saveTimes(cur);_timestamps.value=cur}}
 fun refreshTimestampLines(){val old=_timestamps.value;_timestamps.value=_script.value.mapIndexed{i,t->TimestampLine(i,t,old.getOrNull(i)?.millis)}}
 fun setLastPdf(uri:Uri){prefs.edit().putString("last_pdf",uri.toString()).apply();_last.value=uri.toString()}
 fun setLayout(config:LayoutConfig){val o=JSONObject().apply{put("left",config.boxLeft);put("top",config.boxTop);put("width",config.boxWidth);put("height",config.boxHeight);put("count",config.lineCount);put("speed",config.scrollSpeed);put("lines",JSONArray().apply{config.lines.take(config.lineCount).forEach{l->put(JSONObject().apply{put("u",l.unlocked);put("w",l.widthFraction);put("h",l.heightFraction);put("c",l.cornerFraction)})}})};prefs.edit().putString("layout",o.toString()).putBoolean("layout_ready",true).apply();_layout.value=config}
 fun timestampFileName()="ScriptTimestamps_"+SimpleDateFormat("yyyy-MM-dd_HH-mm",Locale.US).format(Date())+".pdf"
 private fun loadLines():List<String>=runCatching{val raw=prefs.getString("script_lines",null)?:return emptyList();val a=JSONArray(raw);List(a.length()){a.optString(it)}}.getOrDefault(emptyList())
 private fun loadTimes():List<TimestampLine>{val lines=loadLines();val a=runCatching{JSONArray(prefs.getString("timestamps","[]"))}.getOrElse{JSONArray()};return lines.mapIndexed{i,t->TimestampLine(i,t,a.optJSONObject(i)?.takeIf{it.has("millis") }?.optLong("millis"))}}
 private fun saveTimes(items:List<TimestampLine>){val a=JSONArray();items.forEach{a.put(JSONObject().apply{put("index",it.index);put("millis",it.millis)})};prefs.edit().putString("timestamps",a.toString()).apply()}
 private fun loadLayout():LayoutConfig=runCatching{val o=JSONObject(prefs.getString("layout",null)?:return LayoutConfig());val count=o.optInt("count",5).coerceIn(1,50);val a=o.optJSONArray("lines")?:JSONArray();LayoutConfig(o.optDouble("left",.08).toFloat(),o.optDouble("top",.18).toFloat(),o.optDouble("width",.84).toFloat(),o.optDouble("height",.45).toFloat(),count,o.optInt("speed",0).coerceIn(0,9),MutableList(count){i->val x=a.optJSONObject(i)?:JSONObject();LineShape(x.optBoolean("u",false),x.optDouble("w",1.0).toFloat(),x.optDouble("h",1.0).toFloat(),x.optDouble("c",0.0).toFloat())})}.getOrElse{LayoutConfig()}
 companion object{@Volatile private var instance:SessionStore?=null;fun get(c:Context):SessionStore=instance?:synchronized(this){instance?:SessionStore(c.applicationContext).also{instance=it}}}
}
