package com.futurethinking.timestampgenius.pdf
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.futurethinking.timestampgenius.ocr.OcrWord
import com.futurethinking.timestampgenius.ocr.ScriptOcr
import com.futurethinking.timestampgenius.ocr.YellowSeparatorDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
object PdfScriptReader{suspend fun extractLines(context:Context,uri:Uri):List<String>=withContext(Dispatchers.Default){val pfd=context.contentResolver.openFileDescriptor(uri,"r")?:error("Could not open the PDF");pfd.use{d->PdfRenderer(d).use{renderer->{if(renderer.pageCount==0)error("The PDF has no pages");val all=ArrayList<String>();for(pi in 0 until renderer.pageCount){renderer.openPage(pi).use{page->val scale=2f;val bmp=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(600),(page.height*scale).toInt().coerceAtLeast(800),Bitmap.Config.ARGB_8888);bmp.eraseColor(0xFFFFFFFF.toInt());page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);val bands=YellowSeparatorDetector.findBands(bmp);if(bands.isEmpty())error("No yellow separators were found on PDF page "+(pi+1)+". The yellow separators define every script line.");val words=ScriptOcr.recognize(bmp);all+=segments(words,bands,bmp.height);bmp.recycle()}};all.map{it.replace(Regex("\\s+")," ").trim()}.filter{it.isNotBlank()}}}}}}
private fun segments(words:List<OcrWord>,bands:List<IntRange>,height:Int):List<String>{val bounds=bands.map{(it.first+it.last)/2}.sorted();val out=ArrayList<String>();var top=0;for(y in bounds){out+=words.filter{it.top>=top&&it.bottom<=y}.sortedWith(compareBy<OcrWord>{it.top}.thenBy{it.left}).joinToString(" "){it.text};top=y};out+=words.filter{it.top>=top&&it.bottom<=height}.sortedWith(compareBy<OcrWord>{it.top}.thenBy{it.left}).joinToString(" "){it.text};return out}}
