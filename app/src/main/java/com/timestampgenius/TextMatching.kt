package com.timestampgenius

import android.icu.text.Transliterator
import kotlin.math.max

object TextMatching {
    private val toLatin by lazy {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    }

    fun normalize(text: String): String {
        val latin = runCatching { toLatin?.transliterate(text) ?: text }.getOrDefault(text)
        return latin.lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun tokens(text: String): List<String> = normalize(text).split(" ").filter { it.isNotBlank() }

    fun score(expected: String, heard: String): Float {
        val a = tokens(expected)
        val b = tokens(heard)
        if (a.isEmpty() || b.isEmpty()) return 0f
        val window = if (b.size >= a.size) b.takeLast(a.size) else b
        val seq = sequenceSimilarity(a, window)
        val setScore = a.toSet().intersect(b.toSet()).size.toFloat() / a.toSet().size
        return (seq * 0.7f + setScore * 0.3f).coerceIn(0f, 1f)
    }

    fun completion(expected: String, heard: String): Boolean {
        val a = tokens(expected)
        val b = tokens(heard)
        if (a.isEmpty() || b.isEmpty()) return false
        val n = minOf(a.size, b.size)
        val tail = b.takeLast(n)
        val seq = sequenceSimilarity(a, tail)
        val overlap = a.toSet().intersect(b.toSet()).size.toFloat() / a.toSet().size
        val minWords = max(2, (a.size * 0.65f).toInt())
        return n >= minWords && seq >= 0.70f && overlap >= 0.60f
    }

    private fun sequenceSimilarity(a: List<String>, b: List<String>): Float {
        val dist = levenshtein(a, b)
        return 1f - dist.toFloat() / max(a.size, b.size).coerceAtLeast(1)
    }

    private fun levenshtein(a: List<String>, b: List<String>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                cur[j + 1] = minOf(
                    cur[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.size]
    }
}
