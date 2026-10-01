package com.futurethinking.audiotimestampcapture

data class LineLayout(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val lineCount: Int
) {
    fun normalized() = LineLayout(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
        lineCount.coerceIn(1, 30)
    )
}
data class ScreenLine(val index: Int, val text: String, val detectedAtMs: Long)
data class MatchedLine(val lineIndex: Int, val timestampMs: Long, val script: String, val confidence: Float)
