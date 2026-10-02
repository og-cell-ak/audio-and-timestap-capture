package com.timestampgenius

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatchingTest {
    @Test
    fun exactSentenceCompletes() {
        assertTrue(
            TextMatching.completion(
                "This is the final script line",
                "This is the final script line"
            )
        )
    }

    @Test
    fun punctuationAndCaseAreIgnored() {
        assertTrue(
            TextMatching.score(
                "Hello, this is a test!",
                "HELLO this is a test"
            ) > 0.80f
        )
    }

    @Test
    fun unrelatedTextDoesNotComplete() {
        assertFalse(
            TextMatching.completion(
                "The quick brown fox jumps over the wall",
                "completely different words here"
            )
        )
    }

    @Test
    fun devanagariIsNormalized() {
        assertTrue(
            TextMatching.score(
                "नमस्ते दुनिया",
                "नमस्ते, दुनिया"
            ) > 0.80f
        )
    }
}
