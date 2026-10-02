package com.futurethinking.timestampgenius.ocr
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import kotlin.math.max
import kotlin.math.min
data class OcrWord(val text:String,val left:Int,val top:Int,val right:Int,val bottom:Int)
object ScriptOcr{private val latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);private val hindi=TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
 fun recognize(bitmap:Bitmap):List<OcrWord>{val image=InputImage.fromBitmap(bitmap,0);val docs=listOfNotNull(runCatching{Tasks.await(latin.process(image))}.getOrNull(),runCatching{Tasks.await(hindi.process(image))}.getOrNull());val result=ArrayList<OcrWord>();docs.forEach{doc->doc.textBlocks.forEach{block->block.lines.forEach{line->line.elements.forEach{el->val b=el.boundingBox?:return@forEach;result+=OcrWord(el.text,b.left,b.top,b.right,b.bottom)}}}};return result.sortedWith(compareBy<OcrWord>{it.top}.thenBy{it.left}).fold(ArrayList()){acc,w->if(acc.none{similar(it,w)})acc.apply{add(w)}else acc}}
 private fun similar(a:OcrWord,b:OcrWord):Boolean{val ox=max(0,min(a.right,b.right)-max(a.left,b.left));val oy=max(0,min(a.bottom,b.bottom)-max(a.top,b.top));return ox>0&&oy>0&&a.text.equals(b.text,true)}}
