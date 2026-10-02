package com.timestampgenius

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class PdfScriptReader(
    private val context: Context,
    private val ocr: OcrEngine
) {
    suspend fun extract(uri: Uri): Result<List<String>> = withContext(Dispatchers.Default) {
        try {
            val file = File(context.cacheDir, "script_input.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext Result.failure(IllegalArgumentException("Could not read the selected PDF."))

            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val allLines = mutableListOf<String>()
            var anySeparator = false
            var pending = ""

            try {
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val scale = 2f
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).roundToInt().coerceAtLeast(600),
                            (page.height * scale).roundToInt().coerceAtLeast(800),
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val separators = findYellowSeparators(bmp)

                        if (separators.isEmpty()) {
                            pending = joinText(pending, ocr.readText(bmp))
                        } else {
                            anySeparator = true
                            var cursor = 0
                            for ((start, end) in separators) {
                                val beforeBottom = (start - 4).coerceAtLeast(cursor + 1)
                                if (beforeBottom > cursor + 8) {
                                    val crop = Bitmap.createBitmap(bmp, 0, cursor, bmp.width, beforeBottom - cursor)
                                    pending = joinText(pending, ocr.readText(crop))
                                    crop.recycle()
                                }
                                if (pending.isNotBlank()) {
                                    allLines += pending
                                    pending = ""
                                }
                                cursor = (end + 4).coerceAtMost(bmp.height - 1)
                            }
                            if (cursor < bmp.height - 8) {
                                val crop = Bitmap.createBitmap(bmp, 0, cursor, bmp.width, bmp.height - cursor)
                                pending = joinText(pending, ocr.readText(crop))
                                crop.recycle()
                            }
                        }
                        bmp.recycle()
                    }
                }
            } finally {
                renderer.close()
                pfd.close()
                file.delete()
            }

            if (pending.isNotBlank()) allLines += pending
            val clean = allLines.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }

            when {
                !anySeparator -> Result.failure(IllegalArgumentException("No yellow separator lines were found in the PDF."))
                clean.isEmpty() -> Result.failure(IllegalArgumentException("Yellow separators were found, but no usable script text could be read."))
                else -> Result.success(clean)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun joinText(a: String, b: String): String {
        if (a.isBlank()) return b.trim()
        if (b.isBlank()) return a.trim()
        return "$a $b".replace(Regex("\\s+"), " ").trim()
    }

    private fun findYellowSeparators(bitmap: Bitmap): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = -1
        val step = maxOf(1, bitmap.width / 500)
        for (y in 0 until bitmap.height) {
            var yellow = 0
            var samples = 0
            var x = 0
            while (x < bitmap.width) {
                if (isYellow(bitmap.getPixel(x, y))) yellow++
                samples++
                x += step
            }
            val ratio = if (samples == 0) 0f else yellow.toFloat() / samples
            val isSep = ratio > 0.35f
            if (isSep && start < 0) start = y
            if (!isSep && start >= 0) {
                if (y - start >= 1) out += start to y
                start = -1
            }
        }
        if (start >= 0) out += start to bitmap.height
        return mergeClose(out)
    }

    private fun mergeClose(runs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (runs.isEmpty()) return emptyList()
        val merged = mutableListOf<Pair<Int, Int>>()
        var cur = runs.first()
        for (next in runs.drop(1)) {
            if (next.first - cur.second <= 8) cur = cur.first to next.second
            else {
                merged += cur
                cur = next
            }
        }
        merged += cur
        return merged
    }

    private fun isYellow(c: Int): Boolean {
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        return r > 180 && g > 140 && b < 150 && r > b + 60 && g > b + 30
    }
}
