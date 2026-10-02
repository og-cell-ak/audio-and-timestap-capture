package com.futurethinking.timestampgenius.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.futurethinking.timestampgenius.ocr.OcrWord
import com.futurethinking.timestampgenius.ocr.ScriptOcr
import com.futurethinking.timestampgenius.ocr.YellowSeparatorDetector
import com.futurethinking.timestampgenius.util.ScriptTextCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PdfScriptReader {
    suspend fun extractLines(context: Context, uri: Uri): List<String> = withContext(Dispatchers.Default) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the PDF.")
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) error("The PDF has no pages.")
                val output = ArrayList<String>()
                for (pageIndex in 0 until renderer.pageCount) {
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            (page.width * 2f).toInt().coerceAtLeast(600),
                            (page.height * 2f).toInt().coerceAtLeast(800),
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            bitmap.eraseColor(0xFFFFFFFF.toInt())
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val bands = YellowSeparatorDetector.findBands(bitmap)
                            if (bands.isEmpty()) error("No yellow separators were found on PDF page ${pageIndex + 1}. Yellow separators define every script line.")
                            output += splitByYellowBands(ScriptOcr.recognize(bitmap), bands, bitmap.height)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                output.map(ScriptTextCleaner::clean).filter { it.isNotBlank() }
            }
        }
    }

    private fun splitByYellowBands(words: List<OcrWord>, bands: List<IntRange>, height: Int): List<String> {
        val boundaries = bands.map { (it.first + it.last) / 2 }.sorted()
        val result = ArrayList<String>()
        var top = 0
        for (boundary in boundaries) {
            result += words.asSequence().filter { it.top >= top && it.bottom <= boundary }.sortedWith(compareBy<OcrWord> { it.top }.thenBy { it.left }).joinToString(" ") { it.text }
            top = boundary
        }
        result += words.asSequence().filter { it.top >= top && it.bottom <= height }.sortedWith(compareBy<OcrWord> { it.top }.thenBy { it.left }).joinToString(" ") { it.text }
        return result
    }
}
