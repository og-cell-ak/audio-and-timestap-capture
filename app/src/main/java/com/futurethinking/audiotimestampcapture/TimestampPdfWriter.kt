package com.futurethinking.audiotimestampcapture

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object TimestampPdfWriter{
    fun writeToDownloads(context:Context,rows:List<TimedScript>):Uri?{
        if(rows.isEmpty()||Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)return null
        val doc=PdfDocument()
        val pageWidth=595;val pageHeight=842
        val left=36f;val top=42f;val bottom=800f;val timeWidth=86f;val gap=12f;val textWidth=pageWidth-left-36f-timeWidth-gap
        val timePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=android.graphics.Typeface.DEFAULT_BOLD;textSize=11f;color=android.graphics.Color.BLACK}
        val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=11f;color=android.graphics.Color.BLACK}
        var pageNo=0;var page:PdfDocument.Page?=null;var y=top
        fun begin(){pageNo++;page=doc.startPage(PdfDocument.PageInfo.Builder(pageWidth,pageHeight,pageNo).create());y=top}
        fun end(){page?.let{doc.finishPage(it)};page=null}
        begin()
        for(row in rows.sortedBy{it.lineNumber}){
            val text= if(row.script.isBlank()) "" else row.script
            val prefix=if(row.timestampMs==null)"--:--:--.---" else format(row.timestampMs)
            val words=text.split(Regex("\\s+")).filter{it.isNotBlank()}
            val wrapped=mutableListOf<String>()
            var line=""
            for(word in words){
                val candidate=if(line.isBlank())word else line+" "+word
                if(textPaint.measureText(candidate)>textWidth && line.isNotBlank()){wrapped+=line;line=word}else line=candidate
            }
            if(line.isNotBlank()||text.isBlank())wrapped+=line
            val rowHeight=(wrapped.size*18f).coerceAtLeast(24f)
            if(y+rowHeight>bottom){end();begin()}
            page!!.canvas.drawText(prefix,left,y+12f,timePaint)
            wrapped.forEachIndexed{i,lineText->
                page!!.canvas.drawText(lineText,left+timeWidth+gap,y+12f+i*18f,textPaint)
            }
            y+=rowHeight+8f
        }
        end()

        val name="ScriptTimestamps_"+java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm",java.util.Locale.US).format(java.util.Date())+".pdf"
        val values=ContentValues().apply{
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/ScriptTimestamper")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values) ?: return null
        return runCatching{
            resolver.openOutputStream(uri)?.use{doc.writeTo(it)} ?: error("Cannot open PDF output")
            resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            uri
        }.getOrElse{
            resolver.delete(uri,null,null)
            null
        }.also{doc.close()}
    }

    private fun format(ms:Long)="%02d:%02d:%02d.%03d".format(java.util.Locale.US,ms/3600000,(ms%3600000)/60000,(ms%60000)/1000,ms%1000)
}