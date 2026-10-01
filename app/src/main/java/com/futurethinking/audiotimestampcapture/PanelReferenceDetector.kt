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
    private val standaloneNumber = Regex("""^\s*(\d{1,4})\s*[.)\-:]?\s*$""")
    private val prefixedNumber = Regex("""^\s*(\d{1,4})\s*[.)\-:]?\s+.*$""")

    fun detect(lines: List<OcrLine>): List<PanelReference> =
        lines.mapNotNull { line ->
            val value = line.text.trim()
            val match = standaloneNumber.find(value) ?: prefixedNumber.find(value) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (number !in 1..9999) return@mapNotNull null
            PanelReference(number, "", line.left, line.top)
        }.sortedWith(compareBy<PanelReference> { it.top }.thenBy { it.left })
}
