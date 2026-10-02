package com.timestampgenius.app
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.lifecycleScope
import com.alphacephei.vosk.Model
import com.alphacephei.vosk.Recognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ScriptLine(
    val index: Int,
    var text: String,
    var timestampMs: Long? = null,
    var detected: Boolean = false
)

data class LayoutLine(
    var height: Float = 1f,
    var width: Float = 1f,
    var radius: Float = 0f,
    var unlocked: Boolean = false
)

data class LineLayout(
    var left: Float = 0.08f,
    var top: Float = 0.18f,
    var width: Float = 0.84f,
    var height: Float = 0.38f,
    var lineCount: Int = 5,
    var scrollSpeed: Int = 0,
    val lines: MutableList<LayoutLine> = MutableList(5) { LayoutLine() }
) {
    fun normalize() {
        lineCount = lineCount.coerceIn(1, 30)
        while (lines.size < lineCount) lines += LayoutLine()
        while (lines.size > lineCount) lines.removeLast()
        scrollSpeed = scrollSpeed.coerceIn(0, 9)
        left = left.coerceIn(0f, 0.95f)
        top = top.coerceIn(0f, 0.9f)
        width = width.coerceIn(0.2f, 0.98f - left)
        height = height.coerceIn(0.08f, 0.95f - top)
        lines.forEach {
            it.height = it.height.coerceIn(0.35f, 2f)
            it.width = it.width.coerceIn(0.35f, 1.15f)
            it.radius = it.radius.coerceIn(0f, 40f)
        }
    }
}

data class WordBox(val word: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class RecognitionChunk(val text: String, val words: List<WordTiming>, val final: Boolean)
data class WordTiming(val word: String, val startSec: Float, val endSec: Float, val confidence: Float)

object FuzzyMatcher {
    private val punctuation = Regex("[^\p{L}\p{N}]+")
    fun tokens(text: String): List<String> =
        text.lowercase().replace(punctuation, " ").trim().split(Regex("\s+")).filter { it.isNotBlank() }
    fun similarity(a: String, b: String): Float {
        val aa = tokens(a); val bb = tokens(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0f
        val matched = aa.count { aw -> bb.any { bw -> close(aw, bw) } }
        return matched.toFloat() / maxOf(aa.size, bb.size)
    }
    fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length <= 2 || b.length <= 2) return false
        val d = distance(a, b)
        return d <= maxOf(1, minOf(a.length, b.length) / 4)
    }
    fun progress(expected: List<String>, heard: List<String>): Int {
        if (expected.isEmpty()) return 0
        var p = 0
        for (w in heard.takeLast(expected.size + 8)) if (p < expected.size && close(expected[p], w)) p++
        return p
    }
    fun bestUpcoming(lines: List<String>, current: Int, heard: String, window: Int = 4): Int? {
        val start = current.coerceAtLeast(0)
        val end = minOf(lines.size, start + window + 1)
        var best = 0f; var bestIndex: Int? = null
        for (i in start until end) {
            val score = similarity(lines[i], heard)
            if (score > best) { best = score; bestIndex = i }
        }
        return if (best >= 0.45f) bestIndex else null
    }
    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val old = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = old
            }
        }
        return dp[b.length]
    }
}

object LanguageDetector {
    fun isHindi(text: String): Boolean {
        val devanagari = text.count { it in '\u0900'..'\u097F' }
        val latin = text.count { it.isLetter() && it.lowercaseChar() in 'a'..'z' }
        return devanagari > 0 && devanagari >= latin * 0.15
    }
}
