package com.timestampgenius

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

class OcrEngine {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    suspend fun readText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latinText = runCatching { latin.process(image).await().text.trim() }.getOrDefault("")
        val devanagariText = runCatching { devanagari.process(image).await().text.trim() }.getOrDefault("")
        return chooseText(latinText, devanagariText)
    }

    suspend fun readWords(bitmap: Bitmap, offsetX: Float = 0f, offsetY: Float = 0f): List<OcrWord> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val results = mutableListOf<Text>()
        runCatching { results += latin.process(image).await() }
        runCatching { results += devanagari.process(image).await() }

        val words = mutableListOf<OcrWord>()
        val seen = mutableSetOf<String>()
        for (result in results) {
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        val key = "\${element.text}|\${box.left}|\${box.top}|\${box.right}|\${box.bottom}"
                        if (seen.add(key)) {
                            words += OcrWord(
                                element.text,
                                android.graphics.RectF(
                                    box.left + offsetX,
                                    box.top + offsetY,
                                    box.right + offsetX,
                                    box.bottom + offsetY
                                )
                            )
                        }
                    }
                }
            }
        }
        return words.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun chooseText(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val aHindi = a.count { it in '\u0900'..'\u097F' }
        val bHindi = b.count { it in '\u0900'..'\u097F' }
        return if (bHindi > aHindi) b else if (a.length >= b.length) a else b
    }

    fun close() {
        latin.close()
        devanagari.close()
    }
}
