package com.futurethinking.audiotimestampcapture

data class SpokenSegment(val startMs: Long, val text: String, val confidence: Float)
data class TimedScript(val timestampMs: Long, val panelNumber: Int, val script: String, val confidence: Float)

object TimestampEngine {
    fun build(panelOrder: List<PanelReference>, spokenSegments: List<SpokenSegment>): List<TimedScript> {
        val panels = panelOrder.sortedBy { it.detectedAtMs }
        val speech = spokenSegments.sortedBy { it.startMs }
        return panels.mapIndexedNotNull { index, panel ->
            val nextTime = panels.getOrNull(index + 1)?.detectedAtMs ?: Long.MAX_VALUE
            val inPanel = speech.filter { it.startMs >= panel.detectedAtMs && it.startMs < nextTime }
            if (inPanel.isEmpty()) return@mapIndexedNotNull null
            val clean = inPanel.joinToString(" ") { it.text }
                .replace(Regex("""^\s*\d{1,4}[\).:\-]?\s*"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (clean.isEmpty()) return@mapIndexedNotNull null
            TimedScript(
                timestampMs = panel.detectedAtMs,
                panelNumber = panel.number,
                script = clean,
                confidence = inPanel.map { it.confidence }.average().toFloat()
            )
        }
    }
}
