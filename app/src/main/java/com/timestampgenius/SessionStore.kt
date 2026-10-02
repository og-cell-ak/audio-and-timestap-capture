package com.timestampgenius

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("timestamp_genius", Context.MODE_PRIVATE)

    @Synchronized
    fun setPdfReference(name: String?, lines: List<String>) {
        prefs.edit()
            .putString("pdf_name", name)
            .putString("lines", JSONArray(lines).toString())
            .remove("timestamps")
            .apply()
    }

    @Synchronized
    fun setScreenLines(lines: List<String>) {
        prefs.edit()
            .putString("lines", JSONArray(lines).toString())
            .remove("pdf_name")
            .remove("timestamps")
            .apply()
    }

    @Synchronized
    fun appendScreenLines(lines: List<String>) {
        val existing = getLines().map { it.text }.toMutableList()
        val normalizedExisting = existing.map { TextMatching.normalize(it) }.toMutableList()
        for (candidate in lines) {
            val clean = candidate.replace(Regex("\\s+"), " ").trim()
            if (clean.isBlank()) continue
            val normalized = TextMatching.normalize(clean)
            val alreadyKnown = normalizedExisting.any { old ->
                old.isNotBlank() && (
                    old == normalized ||
                    TextMatching.score(old, normalized) >= 0.92f ||
                    TextMatching.score(normalized, old) >= 0.92f
                )
            }
            if (!alreadyKnown) {
                existing += clean
                normalizedExisting += normalized
            }
        }
        if (existing.isNotEmpty()) {
            prefs.edit().putString("lines", JSONArray(existing).toString()).apply()
        }
    }

    @Synchronized
    fun getPdfName(): String? = prefs.getString("pdf_name", null)

    @Synchronized
    fun getLines(): List<ScriptLine> {
        val raw = prefs.getString("lines", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            val timeArray = prefs.getString("timestamps", null)?.let { JSONArray(it) }
            List(a.length()) { i ->
                val t = timeArray?.optLong(i, -1L) ?: -1L
                ScriptLine(i, a.optString(i), if (t >= 0) t else null, t >= 0)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun setTimestamp(index: Int, timeMs: Long) {
        val lines = getLines()
        if (index !in lines.indices) return
        val a = prefs.getString("timestamps", null)?.let { JSONArray(it) } ?: JSONArray()
        while (a.length() < lines.size) a.put(-1L)
        a.put(index, timeMs.coerceAtLeast(0L))
        prefs.edit().putString("timestamps", a.toString()).apply()
    }

    @Synchronized
    fun markNotDetected(index: Int) {
        val lines = getLines()
        if (index !in lines.indices) return
        val a = prefs.getString("timestamps", null)?.let { JSONArray(it) } ?: JSONArray()
        while (a.length() < lines.size) a.put(-1L)
        a.put(index, -1L)
        prefs.edit().putString("timestamps", a.toString()).apply()
    }

    @Synchronized
    fun clearSession() {
        prefs.edit().remove("pdf_name").remove("lines").remove("timestamps").apply()
    }

    @Synchronized
    fun setLastPdf(uri: String, name: String) {
        prefs.edit().putString("last_pdf_uri", uri).putString("last_pdf_name", name).apply()
    }

    @Synchronized
    fun getLastPdf(): Pair<String, String>? {
        val uri = prefs.getString("last_pdf_uri", null) ?: return null
        val name = prefs.getString("last_pdf_name", "Timestamp.pdf") ?: "Timestamp.pdf"
        return uri to name
    }

    @Synchronized
    fun setRunning(running: Boolean) {
        prefs.edit().putBoolean("running", running).apply()
    }

    fun isRunning(): Boolean = prefs.getBoolean("running", false)

    @Synchronized
    fun saveLayout(layout: LayoutSpec) {
        layout.normalize()
        val lineJson = JSONArray()
        layout.lines.forEach {
            lineJson.put(JSONObject().apply {
                put("height", it.height)
                put("width", it.width)
                put("corner", it.corner)
                put("unlocked", it.unlocked)
            })
        }
        prefs.edit().apply {
            putFloat("box_x", layout.boxX)
            putFloat("box_y", layout.boxY)
            putFloat("box_w", layout.boxW)
            putFloat("box_h", layout.boxH)
            putInt("line_count", layout.lineCount)
            putInt("scroll_speed", layout.scrollSpeed)
            putString("line_specs", lineJson.toString())
            apply()
        }
    }

    fun loadLayout(): LayoutSpec {
        val count = prefs.getInt("line_count", 5).coerceIn(1, 30)
        val a = prefs.getString("line_specs", null)?.let { JSONArray(it) }
        return LayoutSpec(
            boxX = prefs.getFloat("box_x", 0.06f),
            boxY = prefs.getFloat("box_y", 0.20f),
            boxW = prefs.getFloat("box_w", 0.88f),
            boxH = prefs.getFloat("box_h", 0.40f),
            lineCount = count,
            scrollSpeed = prefs.getInt("scroll_speed", 0),
            lines = MutableList(count) { i ->
                val o = a?.optJSONObject(i)
                LineSpec(
                    height = o?.optDouble("height", 1.0)?.toFloat() ?: 1f,
                    width = o?.optDouble("width", 1.0)?.toFloat() ?: 1f,
                    corner = o?.optDouble("corner", 0.12)?.toFloat() ?: 0.12f,
                    unlocked = o?.optBoolean("unlocked", false) ?: false
                )
            }
        ).also { it.normalize() }
    }
}
