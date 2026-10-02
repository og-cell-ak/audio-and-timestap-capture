package com.futurethinking.timestampgenius.pdf
import android.content.ContentValues
import android.content.Context
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
    // The writer uses MediaStore so the final PDF lands in Downloads/ScriptTimestamper without a manual save picker.fun write(context:Context,lines:List<TimestampLine>,name:String):android.net.Uri{require(lines.isNotEmpty()){"There is no script to save."};val doc=PdfDocument();val pw=595;val ph=842;val left=40f;val tsw=92f;val right=555f;val text=Paint(1).apply{color=android.graphics.Color.BLACK;textSize=11f;typeface=Typeface.DEFAULT};val bold=Paint(text).apply{typeface=Typeface.DEFAULT_BOLD};var page:PdfDocument.Page?=null;var y=52f;fun start(){page=doc.startPage(PdfDocument.PageInfo.Builder(pw,ph,doc.pages.size+1).create());y=52f};fun finish(){page?.let{doc.finishPage(it)};page=null};start();for(line in lines){val parts=wrap(text,line.text,right-left-tsw);val row=maxOf(20f,parts.size*16f+6f);if(y+row>ph-42){finish();start()};page!!.canvas.drawText(line.millis?.let(TimestampFormatter::format)?:"--:--:--.---",left,y+12,bold);parts.forEachIndexed{i,s->page!!.canvas.drawText(s,left+tsw,y+12+i*16,text)};y+=row};finish();val r=context.contentResolver;val v=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");if(Build.VERSION.SDK_INT>=29)put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/ScriptTimestamper")};val uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Could not create PDF");try{r.openFileDescriptor(uri,"w")!!.use{pfd->FileOutputStream(pfd.fileDescriptor).use{doc.writeTo(it)}}}catch(t:Throwable){r.delete(uri,null,null);throw t}finally{doc.close()};return uri}
private fun wrap(p:Paint,s:String,w:Float):List<String>{val words=s.trim().split(Regex("\\s+")).filter{it.isNotBlank()};if(words.isEmpty())return listOf("");val out=ArrayList<String>();var cur="";for(word in words){val cand=if(cur.isBlank())word else cur+" "+word;if(p.measureText(cand)<=w)cur=cand else{if(cur.isNotBlank())out+=cur;cur=word}};if(cur.isNotBlank())out+=cur;return out}}
