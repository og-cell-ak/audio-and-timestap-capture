package com.timestampgenius

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayController(
    private val context: Context,
    private val wm: WindowManager,
    private val store: SessionStore,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onSave: () -> Unit
) {
    private val controlParams = WindowManager.LayoutParams(
        124, 124,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 18
        y = 280
    }

    private val highlightParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val editorParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val controls = ControlView(context)
    private val highlight = HighlightView(context)
    private var editor: EditorView? = null
    private var menuOpen = false
    private var autoStretch = false

    fun show() {
        if (controls.parent == null) runCatching { wm.addView(controls, controlParams) }
        if (highlight.parent == null) runCatching { wm.addView(highlight, highlightParams) }
    }

    fun hide() {
        closeEditor()
        runCatching { if (controls.parent != null) wm.removeView(controls) }
        runCatching { if (highlight.parent != null) wm.removeView(highlight) }
    }

    fun updateTimer(text: String) {
        controls.timer = text
        controls.invalidate()
    }

    fun updateRecognition(snapshot: RecognitionSnapshot) {
        highlight.snapshot = snapshot
        highlight.invalidate()
    }

    fun updateGlowCount(count: Int, recognizedText: String) {
        highlight.snapshot = highlight.snapshot.copy(
            glowCount = count.coerceAtLeast(0),
            recognizedText = recognizedText
        )
        highlight.invalidate()
    }

    fun setAutoStretch(enabled: Boolean) {
        autoStretch = enabled
        highlight.invalidate()
    }

    fun setRecording(recording: Boolean) {
        controls.recording = recording
        if (!recording) {
            autoStretch = false
            closeMenu()
        }
        if (highlight.parent == null) runCatching { wm.addView(highlight, highlightParams) }
        controls.invalidate()
        highlight.invalidate()
    }

    fun openLineEditor() {
        if (controls.recording) {
            Toast.makeText(context, "Stop recording before editing lines.", Toast.LENGTH_SHORT).show()
            return
        }
        if (editor != null) return
        closeMenu()
        editor = EditorView(context)
        runCatching { wm.addView(editor, editorParams) }
    }

    private fun openMenu() {
        menuOpen = true
        controlParams.width = 140
        controlParams.height = 360
        controlParams.y = controlParams.y.coerceIn(
            0,
            context.resources.displayMetrics.heightPixels - 360
        )
        runCatching { wm.updateViewLayout(controls, controlParams) }
        controls.invalidate()
    }

    private fun closeMenu() {
        menuOpen = false
        controlParams.width = 124
        controlParams.height = 124
        if (controls.parent != null) runCatching { wm.updateViewLayout(controls, controlParams) }
        controls.invalidate()
    }

    private fun closeEditor() {
        editor?.let { runCatching { if (it.parent != null) wm.removeView(it) } }
        editor = null
    }

    inner class ControlView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downRawY = 0f
        private var moved = false
        var recording = false
        var timer = "00:00:00.000"

        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(235, 25, 25, 25)
            c.drawCircle(62f, 62f, 33f, p)

            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 13f
            c.drawText(if (recording) "REC" else "TG", 62f, 67f, p)

            if (menuOpen) {
                p.color = Color.argb(248, 18, 18, 18)
                c.drawRoundRect(RectF(4f, 126f, 136f, 350f), 14f, 14f, p)
                mini(c, RectF(10f, 134f, 72f, 182f), "START")
                mini(c, RectF(76f, 134f, 130f, 182f), "STOP")
                mini(c, RectF(10f, 190f, 72f, 238f), "SAVE")
                mini(c, RectF(76f, 190f, 130f, 238f), "SET LINES", Color.YELLOW)
                p.color = Color.WHITE
                p.textSize = 10f
                p.typeface = Typeface.DEFAULT
                c.drawText(timer, 70f, 278f, p)
            }
        }

        private fun mini(c: Canvas, r: RectF, label: String, textColor: Int = Color.WHITE) {
            p.color = Color.argb(235, 55, 55, 55)
            c.drawRoundRect(r, 8f, 8f, p)
            p.color = textColor
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            p.textSize = 10f
            c.drawText(label, r.centerX(), r.centerY() + 3.5f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (!menuOpen && abs(dy) > 5f) {
                        moved = true
                        controlParams.y = (controlParams.y + dy.toInt()).coerceIn(
                            0,
                            context.resources.displayMetrics.heightPixels - 124
                        )
                        downRawY = e.rawY
                        wm.updateViewLayout(this, controlParams)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!menuOpen && !moved) {
                        openMenu()
                        return true
                    }
                    if (menuOpen) {
                        when {
                            e.y in 134f..182f && e.x < 74f -> onStart()
                            e.y in 134f..182f && e.x >= 74f -> onStop()
                            e.y in 190f..238f && e.x < 74f -> onSave()
                            e.y in 190f..238f && e.x >= 74f -> openLineEditor()
                        }
                    }
                    return true
                }
            }
            return true
        }
    }

    inner class HighlightView(ctx: Context) : View(ctx) {
        var snapshot = RecognitionSnapshot(0, emptyList(), 0, "")
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val layout = store.loadLayout().also { it.normalize() }
            if (autoStretch) layout.boxH = 1f - layout.boxY

            val box = RectF(
                layout.boxX * width,
                layout.boxY * height,
                (layout.boxX + layout.boxW) * width,
                (layout.boxY + layout.boxH) * height
            )

            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = Color.YELLOW
            c.drawRoundRect(box, 12f, 12f, p)

            for (i in 0 until layout.lineCount) {
                val r = layout.lineRect(width, height, i)
                val radius = (layout.lines[i].corner * 28f).coerceIn(0f, 28f)
                c.drawRoundRect(r, radius, radius, p)
            }

            p.style = Paint.Style.FILL
            val count = snapshot.glowCount.coerceIn(0, snapshot.words.size)
            p.color = Color.argb(150, 255, 215, 0)
            for (i in 0 until count) {
                c.drawRoundRect(snapshot.words[i].bounds, 6f, 6f, p)
            }
        }
    }

    inner class EditorView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var spec = store.loadLayout().also { it.normalize() }
        private var selected = 0
        private var handle = -1

        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.FILL
            p.color = Color.argb(115, 0, 0, 0)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

            val box = RectF(
                spec.boxX * width,
                spec.boxY * height,
                (spec.boxX + spec.boxW) * width,
                (spec.boxY + spec.boxH) * height
            )

            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = Color.YELLOW
            c.drawRoundRect(box, 12f, 12f, p)

            for (i in 0 until spec.lineCount) {
                val r = spec.lineRect(width, height, i)
                p.color = if (i == selected) Color.WHITE else Color.YELLOW
                val radius = (spec.lines[i].corner * 28f).coerceIn(0f, 28f)
                c.drawRoundRect(r, radius, radius, p)
            }

            listOf(
                box.left to box.top,
                box.right to box.top,
                box.left to box.bottom,
                box.right to box.bottom
            ).forEach { (x, y) ->
                p.style = Paint.Style.FILL
                p.color = Color.YELLOW
                c.drawCircle(x, y, 11f, p)
                p.color = Color.BLACK
                c.drawCircle(x, y, 4f, p)
            }

            p.color = Color.argb(248, 18, 18, 18)
            c.drawRect(0f, height - 190f, width.toFloat(), height.toFloat(), p)
            row(c, height - 176f, listOf("LINE−", "LINE+", "SPEED−", "SPEED+", "UNLOCK"))
            row(c, height - 120f, listOf("H+", "H−", "W+", "W−", "C+", "C−"))

            button(c, 10f, height - 62f, "SAVE LAYOUT", 170f, Color.YELLOW)
            button(c, 190f, height - 62f, "CLOSE", 110f, Color.WHITE)

            p.color = Color.WHITE
            p.textAlign = Paint.Align.RIGHT
            p.textSize = 13f
            p.typeface = Typeface.DEFAULT
            c.drawText(
                "Line " + (selected + 1) + "/" + spec.lineCount + "   Scroll " + spec.scrollSpeed,
                width - 14f,
                height - 82f,
                p
            )
        }

        private fun row(c: Canvas, y: Float, labels: List<String>) {
            val gap = 7f
            val w = ((width - gap * (labels.size + 1)) / labels.size).coerceAtLeast(44f)
            labels.forEachIndexed { i, s ->
                button(
                    c,
                    gap + i * (w + gap),
                    y,
                    s,
                    w,
                    if (s == "UNLOCK") Color.YELLOW else Color.WHITE
                )
            }
        }

        private fun button(c: Canvas, x: Float, y: Float, label: String, w: Float, textColor: Int) {
            val r = RectF(x, y, x + w, y + 48f)
            p.style = Paint.Style.FILL
            p.color = Color.argb(235, 58, 58, 58)
            c.drawRoundRect(r, 8f, 8f, p)
            p.color = textColor
            p.textAlign = Paint.Align.CENTER
            p.textSize = 11f
            p.typeface = Typeface.DEFAULT_BOLD
            c.drawText(label, r.centerX(), r.centerY() + 4f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val box = RectF(
                spec.boxX * width,
                spec.boxY * height,
                (spec.boxX + spec.boxW) * width,
                (spec.boxY + spec.boxH) * height
            )

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handle = handleAt(box, e.x, e.y)
                    if (handle < 0 && e.y < height - 190f) selected = findLine(e.x, e.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (handle >= 0) resizeBox(handle, e.x / width, e.y / height)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    handle = -1
                    when {
                        e.y in (height - 176f)..(height - 128f) -> applyTopControl(e.x)
                        e.y in (height - 120f)..(height - 72f) -> applyLineControl(e.x)
                        e.y >= height - 62f -> when {
                            e.x < 180f -> {
                                store.saveLayout(spec)
                                closeEditor()
                            }
                            e.x < 320f -> closeEditor()
                        }
                    }
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun applyTopControl(x: Float) {
            val gap = 7f
            val n = 5
            val w = ((width - gap * (n + 1)) / n).coerceAtLeast(44f)
            val i = ((x - gap) / (w + gap)).toInt()
            when (i) {
                0 -> spec.lineCount = max(1, spec.lineCount - 1)
                1 -> spec.lineCount = min(30, spec.lineCount + 1)
                2 -> spec.scrollSpeed = max(0, spec.scrollSpeed - 1)
                3 -> spec.scrollSpeed = min(9, spec.scrollSpeed + 1)
                4 -> spec.lines[selected].unlocked = !spec.lines[selected].unlocked
            }
            spec.normalize()
            selected = selected.coerceIn(0, spec.lineCount - 1)
        }

        private fun applyLineControl(x: Float) {
            if (!spec.lines[selected].unlocked) return
            val gap = 7f
            val n = 6
            val w = ((width - gap * (n + 1)) / n).coerceAtLeast(44f)
            val i = ((x - gap) / (w + gap)).toInt()
            when (i) {
                0 -> spec.lines[selected].height = min(1.6f, spec.lines[selected].height + 0.1f)
                1 -> spec.lines[selected].height = max(0.35f, spec.lines[selected].height - 0.1f)
                2 -> spec.lines[selected].width = min(1f, spec.lines[selected].width + 0.05f)
                3 -> spec.lines[selected].width = max(0.55f, spec.lines[selected].width - 0.05f)
                4 -> spec.lines[selected].corner = min(0.8f, spec.lines[selected].corner + 0.05f)
                5 -> spec.lines[selected].corner = max(0f, spec.lines[selected].corner - 0.05f)
            }
        }

        private fun resizeBox(h: Int, x: Float, y: Float) {
            when (h) {
                0 -> {
                    val right = spec.boxX + spec.boxW
                    val bottom = spec.boxY + spec.boxH
                    spec.boxX = x.coerceIn(0f, right - 0.05f)
                    spec.boxY = y.coerceIn(0f, bottom - 0.05f)
                    spec.boxW = right - spec.boxX
                    spec.boxH = bottom - spec.boxY
                }
                1 -> spec.boxW = (x - spec.boxX).coerceIn(0.05f, 1f - spec.boxX)
                2 -> spec.boxH = (y - spec.boxY).coerceIn(0.05f, 1f - spec.boxY)
                3 -> {
                    spec.boxW = (x - spec.boxX).coerceIn(0.05f, 1f - spec.boxX)
                    spec.boxH = (y - spec.boxY).coerceIn(0.05f, 1f - spec.boxY)
                }
            }
            spec.normalize()
        }

        private fun handleAt(box: RectF, x: Float, y: Float): Int {
            val points = listOf(
                box.left to box.top,
                box.right to box.top,
                box.left to box.bottom,
                box.right to box.bottom
            )
            return points.indexOfFirst { (px, py) ->
                (x - px) * (x - px) + (y - py) * (y - py) <= 27f * 27f
            }
        }

        private fun findLine(x: Float, y: Float): Int =
            (0 until spec.lineCount).minByOrNull { i ->
                val r = spec.lineRect(width, height, i)
                val dx = x - r.centerX()
                val dy = y - r.centerY()
                dx * dx + dy * dy
            } ?: 0
    }
}
