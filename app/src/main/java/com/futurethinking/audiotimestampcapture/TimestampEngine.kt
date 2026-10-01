package com.futurethinking.audiotimestampcapture

data class SpokenSegment(val startMs: Long, val text: String, val confidence: Float)
data class TimedScript(val timestampMs: Long, val panelNumber: Int, val script: String, val confidence: Float)

object TimestampEngine {
    fun build(panelOrder: List<PanelReference>, spokenSegments: List<SpokenSegment>): List<TimedScript> {
        val ordered = panelOrder.sortedWith(compareBy<PanelReference> { it.top }.thenBy { it.left })
        return ordered.mapIndexedNotNull { index, panel ->
            val segment = spokenSegments.getOrNull(index) ?: return@mapIndexedNotNull null
            TimedScript(
                segment.startMs,
                panel.number,
                segment.text.replace(Regex("""^\s*\d{1,4}[\).:\-]?\s*"""), "").trim(),
                segment.confidence
            )
        }
    }
}
