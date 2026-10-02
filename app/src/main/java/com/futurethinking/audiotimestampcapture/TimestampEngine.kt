package com.futurethinking.audiotimestampcapture

object FuzzyMatcher {
    fun tokens(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
    }

    fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        val previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diagonal = previous[0]
            previous[0] = i
            for (j in 1..b.length) {
                val old = previous[j]
                previous[j] = minOf(
                    previous[j] + 1,
                    previous[j - 1] + 1,
                    diagonal + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                diagonal = old
            }
        }
        return previous[b.length] <= maxOf(1, minOf(a.length, b.length) / 4)
    }
}

object TimestampEngine {
    fun similarity(a: String, b: String): Float {
        val aa = FuzzyMatcher.tokens(a)
        val bb = FuzzyMatcher.tokens(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0f
        val hits = aa.count { aw -> bb.any { bw -> FuzzyMatcher.close(aw, bw) } }
        return hits.toFloat() / maxOf(aa.size, bb.size)
    }

    fun clean(text: String): String {
        return text
            .replace(Regex("""^\s*\d{1,4}\s*[).:\-]?\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
