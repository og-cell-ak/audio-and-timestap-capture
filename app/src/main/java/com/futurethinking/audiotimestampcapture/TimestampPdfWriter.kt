package com.futurethinking.audiotimestampcapture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.graphics.Paint
import android.graphics.pdf.PdfDocument

object TimestampPdfWriter {
    fun writeToDownloads(context: Context, rows: List<TimedScript>): Uri? {
        if (rows.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            color = android.graphics.Color.BLACK
        }
        val width = 595
        val height = 842
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
        var y = 48f

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
            y = 48f
        }

        rows.forEachIndexed { rowIndex, row ->
            val prefix = "[" + format(row.timestampMs) + "] "
            var current = prefix

            row.script.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
                val candidate = if (current == prefix) current + word else current + " " + word
                if (paint.measureText(candidate) > 520f) {
                    page.canvas.drawText(current, 36f, y, paint)
                    y += 20f
                    if (y > 790f) newPage()
                    current = word
                } else {
                    current = candidate
                }
            }

            if (current.isNotBlank()) {
                page.canvas.drawText(current, 36f, y, paint)
                y += 30f
            }

            if (y > 790f && rowIndex != rows.lastIndex) newPage()
        }

        doc.finishPage(page)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "timestamped-script-" + System.currentTimeMillis() + ".pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Audio Timestamp Studio")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: run {
            doc.close()
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { output -> doc.writeTo(output) }
                ?: error("Unable to open Downloads output")
            doc.close()

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            uri
        } catch (t: Throwable) {
            doc.close()
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun format(ms: Long): String {
        val h = ms / 3600000
        val m = (ms % 3600000) / 60000
        val s = (ms % 60000) / 1000
        val x = ms % 1000
        return if (h > 0) "%02d:%02d:%02d.%03d".format(h, m, s, x)
        else "%02d:%02d.%03d".format(m, s, x)
    }
}
