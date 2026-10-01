package com.futurethinking.audiotimestampcapture

data class SpokenSegment(val startMs: Long, val text: String, val confidence: Float)
data class TimedScript(val timestampMs: Long, val panelNumber: Int, val script: String, val confidence: Float)

object TimestampEngine {
    fun match(
        screenLines: List<ScreenLine>,
        spoken: SpokenSegment,
        alreadyMatched: Set<Int>
    ): TimedScript? {
        val speech = normalize(spoken.text)
        if (speech.isEmpty()) return null

        var best: ScreenLine? = null
        var bestScore = 0f
        for (line in screenLines) {
            if (line.index in alreadyMatched) continue
            val score = similarity(speech, normalize(line.text))
            if (score > bestScore) {
                bestScore = score
                best = line
            }
        }

        if (best == null || bestScore < 0.38f) return null

        return TimedScript(
            timestampMs = spoken.startMs,
            panelNumber = best.index,
            script = clean(best.text),
            confidence = (bestScore * spoken.confidence).coerceIn(0f, 1f)
        )
    }

    fun ordered(rows: Collection<TimedScript>): List<TimedScript> =
        rows.sortedBy { it.timestampMs }

    private fun clean(s: String): String =
        s.replace(Regex("""^\s*\d{1,4}[).:\-]?\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalize(s: String): List<String> =
        s.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.length >= 2 }

    private fun similarity(a: List<String>, b: List<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.toSet()
        val sb = b.toSet()
        val intersection = sa.intersect(sb).size.toFloat()
        val union = sa.union(sb).size.toFloat().coerceAtLeast(1f)
        val jaccard = intersection / union
        val containment = intersection / minOf(sa.size, sb.size).toFloat().coerceAtLeast(1f)
        return jaccard * 0.55f + containment * 0.45f
    }
}
