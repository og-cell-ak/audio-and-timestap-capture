package com.futurethinking.audiotimestampcapture

data class OcrLine(val text: String, val left: Float, val top: Float)
data class PanelReference(
    val number: Int,
    val text: String,
    val left: Float,
    val top: Float,
    val detectedAtMs: Long = 0L
)

object PanelReferenceDetector {
    private val numberRegex = Regex("""^\s*(\d{1,4})[\).:\-]?\s*(.*)$""")
    fun detect(lines: List<OcrLine>): List<PanelReference> =
        lines.mapNotNull { line ->
            val m = numberRegex.find(line.text) ?: return@mapNotNull null
            val n = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            PanelReference(n, m.groupValues[2].trim(), line.left, line.top)
        }.sortedWith(compareBy<PanelReference> { it.top }.thenBy { it.left })
}
