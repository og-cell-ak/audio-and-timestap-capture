package com.timestampgenius

import android.graphics.RectF

data class ScriptLine(
    val index: Int,
    val text: String,
    val timestampMs: Long? = null,
    val detected: Boolean = false
)

data class LineSpec(
    var height: Float = 1f,
    var width: Float = 1f,
    var corner: Float = 0.12f,
    var unlocked: Boolean = false
)

data class LayoutSpec(
    var boxX: Float = 0.06f,
    var boxY: Float = 0.20f,
    var boxW: Float = 0.88f,
    var boxH: Float = 0.40f,
    var lineCount: Int = 5,
    var scrollSpeed: Int = 0,
    var lines: MutableList<LineSpec> = MutableList(5) { LineSpec() }
) {
    fun normalize() {
        lineCount = lineCount.coerceIn(1, 30)
        if (lines.size != lineCount) {
            val old = lines.toList()
            lines = MutableList(lineCount) { i -> old.getOrNull(i)?.copy() ?: LineSpec() }
        }
        boxX = boxX.coerceIn(0f, 0.95f)
        boxY = boxY.coerceIn(0f, 0.95f)
        boxW = boxW.coerceIn(0.05f, 1f - boxX)
        boxH = boxH.coerceIn(0.08f, 1f - boxY)
        scrollSpeed = scrollSpeed.coerceIn(0, 9)
    }

    fun lineRect(screenW: Int, screenH: Int, index: Int): RectF {
        normalize()
        val top = boxY * screenH + boxH * screenH * (index.toFloat() / lineCount)
        val bottom = boxY * screenH + boxH * screenH * ((index + 1).toFloat() / lineCount)
        val center = (top + bottom) / 2f
        val baseH = bottom - top
        val spec = lines[index.coerceIn(0, lines.lastIndex)]
        val h = if (spec.unlocked) (baseH * spec.height.coerceIn(0.35f, 1.6f)).coerceAtLeast(12f) else baseH
        val w = if (spec.unlocked) (boxW * screenW * spec.width.coerceIn(0.55f, 1f)) else boxW * screenW
        val x = boxX * screenW + (boxW * screenW - w) / 2f
        return RectF(x, center - h / 2f, x + w, center + h / 2f)
    }
}

data class OcrWord(val text: String, val bounds: RectF)

data class RecognitionSnapshot(
    val lineIndex: Int,
    val words: List<OcrWord>,
    val glowCount: Int,
    val recognizedText: String
)

object AppState {
    const val ACTION_STATUS = "com.timestampgenius.STATUS"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_RUNNING = "running"
    const val EXTRA_SAVED_URI = "saved_uri"
}
