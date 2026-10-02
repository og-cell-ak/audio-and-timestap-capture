package com.timestampgenius

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PdfTimestampWriter(private val context: Context) {

    fun write(lines: List<ScriptLine>): Result<Pair<Uri, String>> {
        if (lines.isEmpty()) {
            return Result.failure(IllegalArgumentException("There are no script lines to save."))
        }

        val stampName = SimpleDateFormat(
            "yyyy-MM-dd_HH-mm",
            Locale.US
        ).format(Date())
        val name = "ScriptTimestamps_" + stampName + ".pdf"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/ScriptTimestamper"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"),
            values
        ) ?: return Result.failure(
            IllegalStateException("Android storage did not accept the PDF.")
        )

        val document = PdfDocument()

        try {
            val pageWidth = 595
            val pageHeight = 842
            val left = 34f
            val top = 36f
            val bottom = 800f
            val timeColumnWidth = 92f
            val textX = left + timeColumnWidth + 10f

            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                textSize = 10.5f
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.5f
            }

            val lineHeight = 16f
            var pageNumber = 1
            var page = document.startPage(
                PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            )
            var canvas = page.canvas
            var y = top

            fun nextPage() {
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page.canvas
                y = top
            }

            for (line in lines) {
                val stamp = if (line.timestampMs != null) {
                    format(line.timestampMs)
                } else {
                    "--:--:--.---"
                }

                val wrapped = wrap(
                    line.text,
                    textPaint,
                    pageWidth - textX.toInt() - 30
                )
                val rowHeight = maxOf(
                    lineHeight + 6f,
                    wrapped.size * lineHeight + 6f
                )

                if (y + rowHeight > bottom) {
                    nextPage()
                }

                canvas.drawText(
                    stamp,
                    left,
                    y + timePaint.textSize,
                    timePaint
                )

                wrapped.forEachIndexed { index, part ->
                    canvas.drawText(
                        part,
                        textX,
                        y + textPaint.textSize + index * lineHeight,
                        textPaint
                    )
                }

                y += rowHeight
            }

            document.finishPage(page)

            resolver.openOutputStream(uri)?.use { output ->
                document.writeTo(output)
            } ?: throw IllegalStateException("Could not open the output PDF stream.")

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)

            Result.success(uri to name)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            Result.failure(t)
        } finally {
            document.close()
        }
    }

    private fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000
        val minutes = (total % 3_600_000) / 60_000
        val seconds = (total % 60_000) / 1_000
        val millis = total % 1_000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            hours,
            minutes,
            seconds,
            millis
        )
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Int): List<String> {
        if (text.isBlank()) return listOf("")

        val output = mutableListOf<String>()
        var current = ""

        for (word in text.split(Regex("\\s+"))) {
            val candidate = if (current.isBlank()) word else current + " " + word
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) output += current
                current = word
            }
        }

        if (current.isNotBlank()) output += current
        return output
    }
}
