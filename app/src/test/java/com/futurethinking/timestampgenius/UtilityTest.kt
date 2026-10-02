package com.futurethinking.timestampgenius

import com.futurethinking.timestampgenius.util.FuzzyMatcher
import com.futurethinking.timestampgenius.util.ScriptTextCleaner
import com.futurethinking.timestampgenius.util.TimestampFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityTest {
    @Test
    fun timestampFormat() {
        assertEquals("00:01:02.345", TimestampFormatter.format(62_345))
    }

    @Test
    fun fuzzyMatch() {
        assertTrue(FuzzyMatcher.similarity("hello", "hello") > 0.99f)
        assertTrue(FuzzyMatcher.progress("hello world", "hello wrld") > 0.45f)
    }

    @Test
    fun referenceNumbersAreRemoved() {
        assertEquals("Hello world", ScriptTextCleaner.clean("  [12]. Hello world "))
        assertEquals("Hello world", ScriptTextCleaner.clean("12: Hello world"))
        assertEquals("Hello world", ScriptTextCleaner.clean("१२) Hello world"))
    }
}
