package com.futurethinking.timestampgenius.util

object FuzzyMatcher {
    fun normalize(value: String): List<String> {
        return value.lowercase().replace("[^\\p{L}\\p{Nd}']+".toRegex(), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    }
    fun progress(expected: String, spoken: String): Float {
        val exp = normalize(expected)
        val got = normalize(spoken)
        if (exp.isEmpty() || got.isEmpty()) return 0f
        var cursor = 0
        var matched = 0
        for (word in got.takeLast(48)) {
            while (cursor < exp.size && similarity(exp[cursor], word) < 0.68f) cursor++
            if (cursor < exp.size && similarity(exp[cursor], word) >= 0.68f) { matched++; cursor++ }
        }
        return (matched.toFloat() / exp.size).coerceIn(0f, 1f)
    }
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.length < 2 || b.length < 2) return 0f
        return 1f - levenshtein(a, b).toFloat() / maxOf(a.length, b.length)
    }
    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = current
        }
        return previous[b.length]
    }
}
