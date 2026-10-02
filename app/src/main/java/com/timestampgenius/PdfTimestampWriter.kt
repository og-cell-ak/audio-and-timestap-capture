package com.timestampgenius

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.Locale

class PdfTimestampWriter(private val context: Context) {
    fun write(lines: List<ScriptLine>): Result<Pair<Uri, String>> {
        if (lines.isEmpty()) return Result.failure(IllegalArgumentException("There are no script lines to save."))

        val name = "ScriptTimestamps_\${java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(java.util.Date())}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScriptTimestamper")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: return Result.failure(IllegalStateException("Android storage did not accept the PDF."))

        val doc = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val left = 34f
            val top = 36f
            val bottom = 800f
            val timeCol = 92f
            val textX = left + timeCol + 10f

            val paintTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                textSize = 10.5f
            }
            val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f }
            val lineHeight = 16f

            var pageNumber = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            var y = top

            fun newPage() {
                doc.finishPage(page)
                pageNumber += 1
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = top
            }

            for (line in lines) {
                val stamp = line.timestampMs?.let(::format) ?: "--:--:--.---"
                val wrapped = wrap(line.text, paintText, pageWidth - textX.toInt() - 30)
                val rowHeight = (wrapped.size * lineHeight + 6f).coerceAtLeast(lineHeight + 6f)
                if (y + rowHeight > bottom) newPage()

                canvas.drawText(stamp, left, y + paintTime.textSize, paintTime)
                wrapped.forEachIndexed { i, part ->
                    canvas.drawText(part, textX, y + paintText.textSize + i * lineHeight, paintText)
                }
                y += rowHeight
            }

            doc.finishPage(page)
            resolver.openOutputStream(uri)?.use { doc.writeTo(it) }
                ?: throw IllegalStateException("Could not open the output PDF stream.")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Result.success(uri to name)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return Result.failure(e)
        } finally {
            doc.close()
        }
    }

    private fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1_000
        val milli = total % 1_000
        return "%02d:%02d:%02d.%03d".format(Locale.US, h, m, s, milli)
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isBlank()) return listOf("")
        val out = mutableListOf<String>()
        var current = ""
        for (word in text.split(Regex("\\s+"))) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) current = candidate
            else {
                if (current.isNotBlank()) out += current
                current = word
            }
        }
        if (current.isNotBlank()) out += current
        return out
    }
}
