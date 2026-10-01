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
    private val numberAtStart = Regex("""^\s*([0-9]{1,4})\s*(?:[.)\-:]|$)""")

    fun detect(lines: List<OcrLine>): List<PanelReference> {
        return lines.mapNotNull { line ->
            val normalized = line.text
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .trim()

            val match = numberAtStart.find(normalized) ?: return@mapNotNull null
            val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            if (number !in 1..9999) return@mapNotNull null

            PanelReference(
                number = number,
                text = normalized,
                left = line.left,
                top = line.top
            )
        }.sortedWith(compareBy<PanelReference> { it.top }.thenBy { it.left })
    }
}
