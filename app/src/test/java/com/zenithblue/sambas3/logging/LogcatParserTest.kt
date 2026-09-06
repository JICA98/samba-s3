package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatParserTest {
    @Test
    fun threadtime_line_parses() {
        val parser = LogcatParser { 1_700_000_000_000L }
        val parsed = parser.parse("09-06 12:01:02.345  1234  5678 E RPCS3 : Access violation")
        assertFalse(parsed.raw)
        assertEquals(LogLevel.ERROR, parsed.level)
        assertEquals("RPCS3", parsed.tag)
        assertEquals("Access violation", parsed.message)
        assertEquals("09-06 12:01:02.345", parsed.timestampText)
        assertNotNull(parsed.timestampMs)
        assertEquals(0L, parser.parseErrors)
    }

    @Test
    fun odd_tag_spacing_parses() {
        val parsed = LogcatParser().parse("09-06 12:01:02.345  12  34 I sys_log   : hello")
        assertEquals("sys_log", parsed.tag)
        assertEquals("hello", parsed.message)
        assertFalse(parsed.raw)
    }

    @Test
    fun continuation_line_is_preserved() {
        val parser = LogcatParser()
        parser.parse("09-06 12:01:02.345  1  1 E RPCS3 : first")
        val cont = parser.parse("    at native (pputhread.cpp:12)")
        assertTrue(cont.continuation)
        assertTrue(cont.raw)
        assertEquals("    at native (pputhread.cpp:12)", cont.message)
        assertEquals(1L, parser.continuations)
    }

    @Test
    fun nonmatching_line_preserved_as_raw() {
        val parser = LogcatParser()
        val parsed = parser.parse("not a logcat line")
        assertTrue(parsed.raw)
        assertFalse(parsed.continuation)
        assertEquals("not a logcat line", parsed.message)
        assertEquals(1L, parser.parseErrors)
        assertEquals(1L, parser.rawLines)
        assertNull(parsed.timestampMs)
    }

    @Test
    fun fatal_and_error_levels() {
        val parser = LogcatParser()
        assertEquals(LogLevel.FATAL, parser.parse("09-06 12:01:02.345  1  1 F libc : Fatal signal 11").level)
        assertEquals(LogLevel.ERROR, parser.parse("09-06 12:01:02.345  1  1 E RPCS3 : boom").level)
    }
}
