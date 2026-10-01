package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File

object TimestampPdfWriter {
    fun write(context: Context, rows: List<TimedScript>): File {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
        val w = 595
        val h = 842
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
        var y = 48f

        fun newPage() {
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            y = 48f
        }

        rows.forEach { row ->
            val prefix = "[${format(row.timestampMs)}] "
            var current = prefix
            row.script.split(" ").forEach { word ->
                val candidate = if (current.endsWith(" ")) current + word else "$current $word"
                if (paint.measureText(candidate) > 520f) {
                    page.canvas.drawText(current, 36f, y, paint)
                    y += 20f
                    current = word
                    if (y > 790f) newPage()
                } else current = candidate
            }
            if (current.isNotEmpty()) {
                page.canvas.drawText(current, 36f, y, paint)
                y += 30f
            }
            if (y > 790f && row != rows.last()) newPage()
        }

        doc.finishPage(page)
        val out = File(context.getExternalFilesDir(null), "timestamped-script.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
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
