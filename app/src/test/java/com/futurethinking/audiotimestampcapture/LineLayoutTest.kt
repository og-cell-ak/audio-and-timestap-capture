package com.futurethinking.audiotimestampcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineLayoutTest {
    @Test
    fun defaultLayoutKeepsFiveEqualLines() {
        val layout = LineLayout(
            0.08f, 0.18f, 0.92f, 0.62f, 5, 0,
            List(5) { LineShape() }
        ).normalized()

        assertEquals(5, layout.lineCount)
        assertEquals(5, layout.shapes.size)
        assertEquals(0, layout.scrollSpeed)
        assertTrue(layout.shapes.all { it.width == 1f && it.height == 1f })
    }

    @Test
    fun layoutClampsUnsafeValues() {
        val layout = LineLayout(
            -1f, 2f, 5f, -2f, 999, 99,
            emptyList()
        ).normalized()

        assertEquals(30, layout.lineCount)
        assertEquals(30, layout.shapes.size)
        assertEquals(9, layout.scrollSpeed)
        assertTrue(layout.left >= 0f)
        assertTrue(layout.top >= 0f)
        assertTrue(layout.right <= 1f)
        assertTrue(layout.bottom >= 0.08f)
    }
}
