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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenOcrCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val onPanel: (PanelReference) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var lastNumber: Int? = null
    private var lastScanMs = 0L

    fun start() {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (now - lastScanMs < 900L) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastScanMs = now
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val plane = image.planes[0]
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            image.close()
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrLine(
                                line.text.trim(),
                                line.boundingBox?.left?.toFloat() ?: 0f,
                                line.boundingBox?.top?.toFloat() ?: 0f
                            )
                        }
                    }
                    val refs = PanelReferenceDetector.detect(lines)
                    val candidate = refs.firstOrNull { it.number > 0 }
                    if (candidate != null && candidate.number != lastNumber) {
                        lastNumber = candidate.number
                        onPanel(candidate)
                    }
                }
                .addOnCompleteListener { bitmap.recycle() }
        }, handler)

        display = projection.createVirtualDisplay(
            "AudioTimestampScreen",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
    }

    fun stop() {
        display?.release()
        reader?.close()
        recognizer.close()
        display = null
        reader = null
    }
}
