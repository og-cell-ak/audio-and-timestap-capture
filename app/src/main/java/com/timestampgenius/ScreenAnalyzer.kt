package com.timestampgenius

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScreenAnalyzer(
    private val imageReader: ImageReader,
    private val layout: LayoutSpec,
    private val ocr: OcrEngine,
    private val currentLineProvider: () -> Int,
    private val onSnapshot: (RecognitionSnapshot) -> Unit,
    private val onTextLines: (List<String>) -> Unit,
    private val onAutoStretch: (Boolean) -> Unit
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    runCatching { analyze(image) }
                    image.close()
                }
                delay(600)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun analyze(image: Image) {
        val bitmap = imageToBitmap(image) ?: return
        try {
            val allWords = ocr.readWords(bitmap)
            val lineWords = MutableList(layout.lineCount) { mutableListOf<OcrWord>() }

            for (word in allWords) {
                val cx = word.bounds.centerX()
                val cy = word.bounds.centerY()
                for (i in 0 until layout.lineCount) {
                    if (layout.lineRect(bitmap.width, bitmap.height, i).contains(cx, cy)) {
                        lineWords[i] += word
                        break
                    }
                }
            }

            val detectedLines = lineWords.map { words ->
                words.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            }

            val boxBottom = layout.boxY + layout.boxH
            val hasTextBelowBox = allWords.count {
                it.bounds.centerY() / bitmap.height.toFloat() > boxBottom + 0.01f
            } >= 2
            onAutoStretch(hasTextBelowBox)

            if (detectedLines.any { it.isNotBlank() }) onTextLines(detectedLines)

            val current = currentLineProvider().coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
            onSnapshot(
                RecognitionSnapshot(
                    current,
                    lineWords.getOrNull(current).orEmpty(),
                    0,
                    detectedLines.getOrNull(current).orEmpty()
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return runCatching {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val width = image.width + rowPadding / pixelStride
            val bmp = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            if (width != image.width) {
                Bitmap.createBitmap(bmp, 0, 0, image.width, image.height).also { bmp.recycle() }
            } else bmp
        }.getOrNull()
    }
}
