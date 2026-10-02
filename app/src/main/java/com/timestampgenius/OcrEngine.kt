package com.timestampgenius

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrEngine {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    suspend fun readText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latinText = runCatching { latin.process(image).await().text.trim() }.getOrDefault("")
        val devanagariText = runCatching { devanagari.process(image).await().text.trim() }.getOrDefault("")
        return chooseText(latinText, devanagariText)
    }

    suspend fun readWords(
        bitmap: Bitmap,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): List<OcrWord> {
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
                        val bounds = element.boundingBox ?: continue
                        val key = element.text + "|" + bounds.left + "|" + bounds.top + "|" +
                            bounds.right + "|" + bounds.bottom
                        if (seen.add(key)) {
                            words += OcrWord(
                                text = element.text,
                                bounds = RectF(
                                    bounds.left + offsetX,
                                    bounds.top + offsetY,
                                    bounds.right + offsetX,
                                    bounds.bottom + offsetY
                                )
                            )
                        }
                    }
                }
            }
        }

        return words.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun chooseText(first: String, second: String): String {
        if (first.isBlank()) return second
        if (second.isBlank()) return first

        val firstHindi = first.count { it in 'ऀ'..'ॿ' }
        val secondHindi = second.count { it in 'ऀ'..'ॿ' }

        return when {
            secondHindi > firstHindi -> second
            first.length >= second.length -> first
            else -> second
        }
    }

    fun close() {
        latin.close()
        devanagari.close()
    }
}
