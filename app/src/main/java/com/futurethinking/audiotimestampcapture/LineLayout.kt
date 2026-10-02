package com.futurethinking.audiotimestampcapture

data class LineShape(
    var width: Float = 1f,
    var height: Float = 1f,
    var radius: Float = 0f,
    var unlocked: Boolean = false
)

data class LineLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val lineCount: Int,
    val scrollSpeed: Int = 0,
    val shapes: List<LineShape> = List(lineCount.coerceIn(1,30)) { LineShape() }
) {
    fun normalized(): LineLayout {
        val count = lineCount.coerceIn(1,30)
        val safeShapes = MutableList(count) { i ->
            (shapes.getOrNull(i) ?: LineShape()).copy(
                width = (shapes.getOrNull(i)?.width ?: 1f).coerceIn(.35f,1.15f),
                height = (shapes.getOrNull(i)?.height ?: 1f).coerceIn(.35f,2f),
                radius = (shapes.getOrNull(i)?.radius ?: 0f).coerceIn(0f,40f)
            )
        }
        return LineLayout(
            left.coerceIn(0f,.95f),
            top.coerceIn(0f,.9f),
            right.coerceIn(.05f,1f),
            bottom.coerceIn(.08f,1f),
            count,
            scrollSpeed.coerceIn(0,9),
            safeShapes
        )
    }
}

data class ScreenLine(
    val index: Int,
    val text: String,
    val detectedAtMs: Long,
    val wordBoxes: List<WordBox> = emptyList()
)

data class SpokenWord(val word: String, val startSec: Float, val endSec: Float, val confidence: Float)
data class SpokenSegment(val startMs: Long, val endMs: Long, val text: String, val words: List<SpokenWord>, val confidence: Float)
data class TimedScript(val timestampMs: Long?, val lineNumber: Int, val script: String, val confidence: Float, val detected: Boolean)

data class WordBox(
    val word: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
