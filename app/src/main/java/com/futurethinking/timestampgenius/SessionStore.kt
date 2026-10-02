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

class SessionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("timestamp_genius", Context.MODE_PRIVATE)
    private val _script = MutableStateFlow(loadLines())
    val scriptLines: StateFlow<List<String>> = _script
    private val _timestamps = MutableStateFlow(loadTimes())
    val timestamps: StateFlow<List<TimestampLine>> = _timestamps
    private val _last = MutableStateFlow(prefs.getString("last_pdf", null))
    val lastPdf: StateFlow<String?> = _last
    private val _layout = MutableStateFlow(loadLayout())
    val layout: StateFlow<LayoutConfig> = _layout
    val layoutReady: Boolean get() = prefs.getBoolean("layout_ready", false)

    fun setScript(uri: Uri?, lines: List<String>) {
        prefs.edit().putString("script_uri", uri?.toString()).putString("script_lines", JSONArray(lines).toString()).remove("timestamps").apply()
        _script.value = lines
        refreshTimestampLines()
    }

    fun replaceScript(lines: List<String>) = setScript(null, lines)

    fun clearCurrentSession() {
        prefs.edit().remove("script_uri").remove("script_lines").remove("timestamps").remove("last_pdf").apply()
        _script.value = emptyList()
        _timestamps.value = emptyList()
        _last.value = null
    }

    fun recordTimestamp(index: Int, elapsed: Long) {
        val lines = _script.value
        if (index !in lines.indices) return
        val current = _timestamps.value.toMutableList()
        while (current.size < lines.size) {
            val i = current.size
            current += TimestampLine(i, lines[i], null)
        }
        if (current[index].millis == null) {
            current[index] = current[index].copy(millis = elapsed)
            saveTimes(current)
            _timestamps.value = current
        }
    }

    fun refreshTimestampLines() {
        val old = _timestamps.value
        _timestamps.value = _script.value.mapIndexed { index, text -> TimestampLine(index, text, old.getOrNull(index)?.millis) }
    }

    fun setLastPdf(uri: Uri) {
        prefs.edit().putString("last_pdf", uri.toString()).apply()
        _last.value = uri.toString()
    }

    fun setLayout(config: LayoutConfig) {
        val root = JSONObject().put("left", config.boxLeft).put("top", config.boxTop).put("width", config.boxWidth).put("height", config.boxHeight).put("count", config.lineCount).put("speed", config.scrollSpeed)
        val storedLines = JSONArray()
        config.lines.take(config.lineCount).forEach { line ->
            storedLines.put(JSONObject().put("u", line.unlocked).put("w", line.widthFraction).put("h", line.heightFraction).put("c", line.cornerFraction))
        }
        root.put("lines", storedLines)
        prefs.edit().putString("layout", root.toString()).putBoolean("layout_ready", true).apply()
        _layout.value = config
    }

    fun timestampFileName(): String {
        return "ScriptTimestamps_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.pdf"
    }

    private fun loadLines(): List<String> {
        return try {
            val raw = prefs.getString("script_lines", null) ?: return emptyList()
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "").trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    private fun loadTimes(): List<TimestampLine> {
        val lines = loadLines()
        val array = try { JSONArray(prefs.getString("timestamps", "[]")) } catch (_: Throwable) { JSONArray() }
        return lines.mapIndexed { index, text ->
            val item = array.optJSONObject(index)
            TimestampLine(index, text, if (item?.has("millis") == true) item.optLong("millis") else null)
        }
    }

    private fun saveTimes(items: List<TimestampLine>) {
        val array = JSONArray()
        items.forEach { item ->
            val value = JSONObject().put("index", item.index)
            item.millis?.let { value.put("millis", it) }
            array.put(value)
        }
        prefs.edit().putString("timestamps", array.toString()).apply()
    }

    private fun loadLayout(): LayoutConfig {
        return try {
            val raw = prefs.getString("layout", null) ?: return LayoutConfig()
            val root = JSONObject(raw)
            val count = root.optInt("count", 5).coerceIn(1, 50)
            val stored = root.optJSONArray("lines") ?: JSONArray()
            LayoutConfig(
                root.optDouble("left", .08).toFloat().coerceIn(0f, 1f),
                root.optDouble("top", .18).toFloat().coerceIn(0f, 1f),
                root.optDouble("width", .84).toFloat().coerceIn(.25f, .95f),
                root.optDouble("height", .45).toFloat().coerceIn(.18f, .85f),
                count,
                root.optInt("speed", 0).coerceIn(0, 9),
                MutableList(count) { i ->
                    val item = stored.optJSONObject(i) ?: JSONObject()
                    LineShape(
                        item.optBoolean("u", false),
                        item.optDouble("w", 1.0).toFloat().coerceIn(.4f, 1f),
                        item.optDouble("h", 1.0).toFloat().coerceIn(.35f, 1.4f),
                        item.optDouble("c", 0.0).toFloat().coerceIn(0f, 1f)
                    )
                }
            )
        } catch (_: Throwable) { LayoutConfig() }
    }

    companion object {
        @Volatile private var instance: SessionStore? = null
        fun get(context: Context): SessionStore = instance ?: synchronized(this) { instance ?: SessionStore(context.applicationContext).also { instance = it } }
    }
}
