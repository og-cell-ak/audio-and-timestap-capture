package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
    private var captureStartMs = 0L
    private var processing = false
    private var stopped = false

    fun start() {
        stopped = false
        processing = false
        lastNumber = null
        lastScanMs = 0L
        captureStartMs = SystemClock.elapsedRealtime()

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            if (stopped || processing) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastScanMs < 550L) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastScanMs = now

            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            processing = true

            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * image.width).coerceAtLeast(0)
                val paddedWidth = image.width + rowPadding / pixelStride

                val padded = Bitmap.createBitmap(
                    paddedWidth,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                padded.copyPixelsFromBuffer(plane.buffer)

                val bitmap = if (paddedWidth == image.width) {
                    padded
                } else {
                    Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                        padded.recycle()
                    }
                }

                image.close()

                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        if (stopped) return@addOnSuccessListener

                        val lines = result.textBlocks.flatMap { block ->
                            block.lines.map { line ->
                                OcrLine(
                                    line.text.trim(),
                                    line.boundingBox?.left?.toFloat() ?: 0f,
                                    line.boundingBox?.top?.toFloat() ?: 0f
                                )
                            }
                        }

                        val expected = (lastNumber ?: 0) + 1
                        val candidate = PanelReferenceDetector.detect(lines)
                            .firstOrNull { it.number == expected }

                        if (candidate != null) {
                            lastNumber = candidate.number
                            onPanel(
                                candidate.copy(
                                    detectedAtMs = SystemClock.elapsedRealtime() - captureStartMs
                                )
                            )
                        }
                    }
                    .addOnCompleteListener {
                        processing = false
                        bitmap.recycle()
                    }
            } catch (_: Throwable) {
                image.close()
                processing = false
            }
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
        stopped = true
        display?.release()
        reader?.close()
        recognizer.close()
        display = null
        reader = null
    }
}
