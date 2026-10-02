package com.futurethinking.audiotimestampcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

object PdfScriptParser {
    suspend fun parse(context: Context, uri: Uri): List<StoredScriptLine> {
        val temp=File(context.cacheDir,"timestamp_genius_script.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not read the selected PDF")
        val fd=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer=PdfRenderer(fd)
        val latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hindi=TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        return try {
            val out=mutableListOf<StoredScriptLine>()
            for(pageIndex in 0 until renderer.pageCount) {
                val page=renderer.openPage(pageIndex)
                try {
                    val scale=2
                    val bmp=Bitmap.createBitmap(page.width*scale,page.height*scale,Bitmap.Config.ARGB_8888)
                    page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val bands=YellowSeparatorDetector.findBands(bmp)
                    if(bands.size<2) error("Page " + (pageIndex+1) + " has no usable yellow separators")
                    for(rect in YellowSeparatorDetector.extractSegments(bmp,bands)) {
                        val crop=Bitmap.createBitmap(bmp,rect.left,rect.top,rect.width(),rect.height())
                        try {
                            val text=ocr(latin,hindi,crop)
                            out += StoredScriptLine(out.size+1,text.ifBlank { "[unreadable segment]" })
                        } finally { crop.recycle() }
                    }
                    bmp.recycle()
                } finally { page.close() }
            }
            if(out.isEmpty()) error("No script segments were found between yellow separators")
            out
        } finally {
            latin.close()
            hindi.close()
            renderer.close()
            temp.delete()
        }
    }

    private suspend fun ocr(latin:TextRecognizer,hindi:TextRecognizer,bitmap:Bitmap):String {
        val a=runCatching { latin.process(InputImage.fromBitmap(bitmap,0)).await().text }.getOrDefault("")
        val b=runCatching { hindi.process(InputImage.fromBitmap(bitmap,0)).await().text }.getOrDefault("")
        return listOf(a,b).filter{it.isNotBlank()}.joinToString(" ").replace(Regex("\s+")," ").trim()
    }
}
