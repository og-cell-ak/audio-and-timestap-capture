package com.futurethinking.timestampgenius.pdf

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.futurethinking.timestampgenius.TimestampLine
import com.futurethinking.timestampgenius.util.TimestampFormatter
import java.io.FileOutputStream

object TimestampPdfWriter {

    fun write(
        context: Context,
        lines: List<TimestampLine>,
        name: String
    ): android.net.Uri {
        require(lines.isNotEmpty()) { "There is no script to save." }

        // MediaStore writes directly to Downloads/ScriptTimestamper without a manual save picker.
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val left = 40f
        val timestampWidth = 92f
        val right = 555f

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.DEFAULT
        }

        val timestampPaint = Paint(bodyPaint).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        var page: PdfDocument.Page? = null
        var y = 52f

        fun startPage() {
            page = document.startPage(
                PdfDocument.PageInfo.Builder(
                    pageWidth,
                    pageHeight,
                    document.pages.size + 1
                ).create()
            )
            y = 52f
        }

        fun finishPage() {
            page?.let(document::finishPage)
            page = null
        }

        startPage()

        for (line in lines) {
            val wrapped = wrapText(
                bodyPaint,
                line.text,
                right - left - timestampWidth
            )

            val rowHeight = maxOf(
                20f,
                wrapped.size * 16f + 6f
            )

            if (y + rowHeight > pageHeight - 42f) {
                finishPage()
                startPage()
            }

            val timestamp = line.millis?.let(TimestampFormatter::format)
                ?: "--:--:--.---"

            page!!.canvas.drawText(
                timestamp,
                left,
                y + 12f,
                timestampPaint
            )

            wrapped.forEachIndexed { index, text ->
                page!!.canvas.drawText(
                    text,
                    left + timestampWidth,
                    y + 12f + index * 16f,
                    bodyPaint
                )
            }

            y += rowHeight
        }

        finishPage()

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= 29) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ScriptTimestamper"
                )
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Could not create the PDF in phone storage.")

        try {
            resolver.openFileDescriptor(uri, "w")!!.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    document.writeTo(output)
                }
            }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            document.close()
        }

        return uri
    }

    private fun wrapText(
        paint: Paint,
        text: String,
        width: Float
    ): List<String> {
        val words = text
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return listOf("")

        val output = ArrayList<String>()
        var current = ""

        for (word in words) {
            val candidate = if (current.isBlank()) {
                word
            } else {
                "$current $word"
            }

            if (paint.measureText(candidate) <= width) {
                current = candidate
            } else {
                if (current.isNotBlank()) {
                    output += current
                }
                current = word
            }
        }

        if (current.isNotBlank()) {
            output += current
        }

        return output
    }
}
