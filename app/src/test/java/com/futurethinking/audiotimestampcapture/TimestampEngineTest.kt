package com.futurethinking.audiotimestampcapture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimestampEngineTest {
    @Test
    fun closeAcceptsSmallSpellingDifference() {
        assertTrue(FuzzyMatcher.close("timestamp", "timestmap"))
    }

    @Test
    fun similarityRecognizesSharedLine() {
        val score = TimestampEngine.similarity(
            "This is the next script line",
            "this is next script line"
        )
        assertTrue(score >= 0.75f)
    }

    @Test
    fun cleanRemovesLeadingReferenceNumber() {
        assertEquals(
            "Hello world",
            TimestampEngine.clean("12: Hello world")
        )
    }
}
