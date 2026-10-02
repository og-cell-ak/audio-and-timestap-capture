package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class StoredScriptLine(val index: Int, val text: String, val timestampMs: Long? = null, val detected: Boolean = false)

object SessionStore {
    private const val PREFS = "timestamp_genius_session"
    private const val SCRIPT = "script"
    private const val PDF_URI = "pdf_uri"
    private const val LAST_URI = "last_pdf_uri"
    private const val LEFT = "left"
    private const val TOP = "top"
    private const val RIGHT = "right"
    private const val BOTTOM = "bottom"
    private const val COUNT = "count"
    private const val SPEED = "speed"
    private const val OVERLAY_Y = "overlay_y"
    private const val SHAPES = "shapes"

    fun saveScript(context: Context, lines: List<StoredScriptLine>) {
        val arr = JSONArray()
        lines.forEach { arr.put(JSONObject().apply {
            put("index", it.index); put("text", it.text); put("timestampMs", it.timestampMs ?: JSONObject.NULL); put("detected", it.detected)
        }) }
        prefs(context).edit().putString(SCRIPT, arr.toString()).apply()
    }

    fun loadScript(context: Context): List<StoredScriptLine> {
        val raw = prefs(context).getString(SCRIPT, null) ?: return emptyList()
        return runCatching {
            val a=JSONArray(raw)
            buildList {
                for(i in 0 until a.length()) {
                    val o=a.getJSONObject(i)
                    add(StoredScriptLine(o.getInt("index"),o.getString("text"),if(o.isNull("timestampMs")) null else o.getLong("timestampMs"),o.optBoolean("detected")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveLayout(context: Context, layout: LineLayout) {
        val l=layout.normalized()
        val e=prefs(context).edit()
            .putFloat(LEFT,l.left).putFloat(TOP,l.top).putFloat(RIGHT,l.right).putFloat(BOTTOM,l.bottom)
            .putInt(COUNT,l.lineCount).putInt(SPEED,l.scrollSpeed)
        val a=JSONArray()
        l.shapes.forEach { s -> a.put(JSONObject().apply { put("width",s.width);put("height",s.height);put("radius",s.radius);put("unlocked",s.unlocked) }) }
        e.putString(SHAPES,a.toString()).apply()
    }

    fun loadLayout(context: Context): LineLayout {
        val p=prefs(context)
        val count=p.getInt(COUNT,5)
        val arr = runCatching { JSONArray(p.getString(SHAPES,"[]")) }.getOrDefault(JSONArray())
        val shapes=MutableList(count){ LineShape() }
        for(i in 0 until minOf(count,arr.length())) {
            val o=arr.optJSONObject(i) ?: continue
            shapes[i]=LineShape(o.optFloat("width",1f),o.optFloat("height",1f),o.optFloat("radius",0f),o.optBoolean("unlocked",false))
        }
        return LineLayout(p.getFloat(LEFT,.08f),p.getFloat(TOP,.18f),p.getFloat(RIGHT,.92f),p.getFloat(BOTTOM,.62f),count,p.getInt(SPEED,0),shapes).normalized()
    }

    fun savePdfInput(context: Context, uri: Uri?) = prefs(context).edit().putString(PDF_URI,uri?.toString()).apply()
    fun loadPdfInput(context: Context): Uri? = prefs(context).getString(PDF_URI,null)?.let(Uri::parse)
    fun saveLastPdf(context: Context, uri: Uri?) = prefs(context).edit().putString(LAST_URI,uri?.toString()).apply()
    fun loadLastPdf(context: Context): Uri? = prefs(context).getString(LAST_URI,null)?.let(Uri::parse)
    fun overlayY(context: Context): Int = prefs(context).getInt(OVERLAY_Y,180)
    fun saveOverlayY(context: Context,y:Int)=prefs(context).edit().putInt(OVERLAY_Y,y).apply()

    fun clearSession(context: Context) {
        prefs(context).edit().remove(SCRIPT).remove(PDF_URI).remove(LAST_URI).apply()
        context.cacheDir.deleteRecursively()
        context.filesDir.resolve("session").deleteRecursively()
        // Layout is intentionally retained for the next session.
    }

    private fun prefs(context: Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
}
