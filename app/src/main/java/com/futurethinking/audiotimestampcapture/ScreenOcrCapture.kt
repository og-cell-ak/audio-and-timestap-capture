package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenOcrCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val layoutProvider: () -> LineLayout?,
    private val onLines: (List<ScreenLine>) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastScan = 0L
    private var startMs = 0L
    private var stopped = true
    private var busy = false

    fun start() {
        stopped = false
        busy = false
        lastScan = 0L
        startMs = SystemClock.elapsedRealtime()
        val m = context.resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            if (stopped || busy) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val layout = layoutProvider()
            if (layout == null) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val now = SystemClock.elapsedRealtime()
            if (now - lastScan < 650L) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            lastScan = now
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            busy = true
            try {
                val plane = image.planes[0]
                val paddedWidth = image.width + ((plane.rowStride - plane.pixelStride * image.width).coerceAtLeast(0) / plane.pixelStride)
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                image.close()
                val full = if (paddedWidth == image.width) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                processLines(full, layout, SystemClock.elapsedRealtime() - startMs, 0, mutableListOf())
            } catch (_: Throwable) {
                image.close()
                busy = false
            }
        }, handler)
        display = projection.createVirtualDisplay(
            "AudioTimestampScreen",
            w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
    }

    private fun processLines(
        full: Bitmap, layout: LineLayout, nowMs: Long, index: Int, output: MutableList<ScreenLine>
    ) {
        if (stopped || index >= layout.lineCount) {
            if (!stopped) onLines(output.toList())
            full.recycle()
            busy = false
            return
        }
        val left = (layout.left * full.width).toInt().coerceIn(0, full.width - 1)
        val right = (layout.right * full.width).toInt().coerceIn(left + 1, full.width)
        val top = (layout.top * full.height + (layout.bottom-layout.top) * full.height * index / layout.lineCount)
            .toInt().coerceIn(0, full.height - 1)
        val bottom = (layout.top * full.height + (layout.bottom-layout.top) * full.height * (index + 1) / layout.lineCount)
            .toInt().coerceIn(top + 1, full.height)
        val crop = Bitmap.createBitmap(full, left, top, right-left, bottom-top)
        val image=InputImage.fromBitmap(crop, 0)
        var completed=0
        var latinText=""
        var devanagariText=""
        fun done(){
            completed++
            if(completed<2) return
            val latin=latinText.replace(Regex("\\s+"), " ").trim()
            val dev=devanagariText.replace(Regex("\\s+"), " ").trim()
            val text=if(dev.length>latin.length) dev else latin
            if(text.isNotEmpty()) output.add(ScreenLine(index + 1, text, nowMs))
            crop.recycle()
            processLines(full, layout, nowMs, index + 1, output)
        }
        latinRecognizer.process(image)
            .addOnSuccessListener { latinText=it.text }
            .addOnCompleteListener { done() }
        devanagariRecognizer.process(image)
            .addOnSuccessListener { devanagariText=it.text }
            .addOnCompleteListener { done() }
    }

    fun stop() {
        stopped = true
        display?.release()
        reader?.close()
        display = null
        reader = null
        busy = false
        latinRecognizer.close()
        devanagariRecognizer.close()
    }
}
