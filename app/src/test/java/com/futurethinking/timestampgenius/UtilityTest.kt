package com.futurethinking.timestampgenius
import com.futurethinking.timestampgenius.util.FuzzyMatcher
import com.futurethinking.timestampgenius.util.TimestampFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class UtilityTest{
 @Test fun timestampFormat(){assertEquals("00:01:02.345",TimestampFormatter.format(62345))}
 @Test fun fuzzyMatch(){assertTrue(FuzzyMatcher.similarity("hello","hello")>.99f);assertTrue(FuzzyMatcher.progress("hello world","hello wrld")>.45f)}
}
