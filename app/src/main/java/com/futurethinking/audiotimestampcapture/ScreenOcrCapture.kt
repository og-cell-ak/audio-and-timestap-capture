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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ScreenOcrCapture(
    private val context: Context,
    private val projection: MediaProjection,
    private val layoutProvider: () -> LineLayout?,
    private val startElapsed: Long,
    private val onLines: (List<ScreenLine>) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val hindi = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var stopped = true
    @Volatile private var busy = false
    private var lastFrame = 0L

    fun start() {
        stopped = false
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ imageReader ->
            if (stopped || busy) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < 500L) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val layout = layoutProvider()
            if (layout == null) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastFrame = now
            busy = true

            scope.launch {
                var full: Bitmap? = null
                try {
                    full = copyImageToBitmap(image)
                    image.close()
                    val lines = readLines(full, layout)
                    full.recycle()
                    full = null
                    if (!stopped) {
                        withContext(Dispatchers.Main) {
                            onLines(lines)
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { image.close() }
                    full?.let { runCatching { it.recycle() } }
                    if (!stopped) {
                        withContext(Dispatchers.Main) {
                            onStatus("OCR warning • " + (t.message ?: "screen read failed"))
                        }
                    }
                } finally {
                    busy = false
                }
            }
        }, handler)

        display = projection.createVirtualDisplay(
            "TimestampGeniusScreen",
            width,
            height,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )
    }

    private fun copyImageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = image.width + (
            (rowStride - pixelStride * image.width).coerceAtLeast(0) / pixelStride
        )

        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)

        return if (paddedWidth == image.width) {
            padded
        } else {
            Bitmap.createBitmap(
                padded,
                0,
                0,
                image.width,
                image.height
            ).also { padded.recycle() }
        }
    }

    private suspend fun readLines(
        full: Bitmap,
        layout: LineLayout
    ): List<ScreenLine> {
        val result = mutableListOf<ScreenLine>()
        val boxTop = layout.top * full.height
        val boxHeight = (layout.bottom - layout.top).coerceAtLeast(0.05f) * full.height
        val rowHeight = boxHeight / layout.lineCount.coerceAtLeast(1)

        for (index in 0 until layout.lineCount) {
            if (stopped) break

            val shape = layout.shapes.getOrNull(index) ?: LineShape()
            val centerY = boxTop + rowHeight * (index + 0.5f)
            val cropHeight = (rowHeight * 0.78f * shape.height)
                .coerceIn(8f, rowHeight * 0.96f)
            val cropWidth = (layout.right - layout.left) * full.width * shape.width
            val centerX = ((layout.left + layout.right) / 2f) * full.width

            val left = (centerX - cropWidth / 2f)
                .toInt()
                .coerceIn(0, full.width - 1)
            val right = (centerX + cropWidth / 2f)
                .toInt()
                .coerceIn(left + 1, full.width)
            val top = (centerY - cropHeight / 2f)
                .toInt()
                .coerceIn(0, full.height - 1)
            val bottom = (centerY + cropHeight / 2f)
                .toInt()
                .coerceIn(top + 1, full.height)

            val crop = Bitmap.createBitmap(
                full,
                left,
                top,
                right - left,
                bottom - top
            )

            try {
                val latinResult = runCatching {
                    latin.process(InputImage.fromBitmap(crop, 0)).await()
                }.getOrNull()

                val hindiResult = runCatching {
                    hindi.process(InputImage.fromBitmap(crop, 0)).await()
                }.getOrNull()

                val best = choose(latinResult, hindiResult)
                val wordBoxes = best
                    ?.textBlocks
                    .orEmpty()
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .mapNotNull { element ->
                        val box = element.boundingBox ?: return@mapNotNull null
                        WordBox(
                            element.text,
                            (left + box.left).toFloat(),
                            (top + box.top).toFloat(),
                            (left + box.right).toFloat(),
                            (top + box.bottom).toFloat()
                        )
                    }

                val text = best?.text
                    ?.replace(Regex("""s+"""), " ")
                    ?.trim()
                    .orEmpty()

                result += ScreenLine(
                    index = index + 1,
                    text = text,
                    detectedAtMs = SystemClock.elapsedRealtime() - startElapsed,
                    wordBoxes = wordBoxes
                )
            } finally {
                crop.recycle()
            }
        }

        return result
    }

    private fun choose(latinText: Text?, hindiText: Text?): Text? {
        val latinValue = latinText?.text?.trim().orEmpty()
        val hindiValue = hindiText?.text?.trim().orEmpty()

        return when {
            latinValue.isBlank() && hindiValue.isBlank() -> null
            hindiValue.length > latinValue.length -> hindiText
            else -> latinText
        }
    }

    fun stop() {
        stopped = true
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        busy = false
        scope.cancel()
        runCatching { latin.close() }
        runCatching { hindi.close() }
    }
}
