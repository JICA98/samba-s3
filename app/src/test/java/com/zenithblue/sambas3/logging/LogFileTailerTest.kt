package com.zenithblue.sambas3.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogFileTailerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun hydrates_existing_non_empty_file() {
        val file = tmp.newFile("RPCSX.log")
        file.writeText("one\ntwo\nthree\n")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("one", "two", "three"), tailer.poll())
    }

    @Test
    fun empty_then_append() {
        val file = tmp.newFile("empty.log")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertTrue(tailer.poll().isEmpty())
        file.appendText("later\n")
        assertEquals(listOf("later"), tailer.poll())
    }

    @Test
    fun file_appears_after_start() {
        val file = File(tmp.root, "late.log")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertTrue(tailer.poll().isEmpty())
        file.writeText("created\n")
        assertEquals(listOf("created"), tailer.poll())
    }

    @Test
    fun truncation_resets_offset() {
        val file = tmp.newFile("trunc.log")
        file.writeText("aaaaaaaa\nbbbbbbbb\n")
        val tailer = LogFileTailer(file, startAtEnd = false)
        tailer.poll()
        file.writeText("new\n")
        assertEquals(listOf("new"), tailer.poll())
    }

    @Test
    fun replacement_is_reread() {
        val file = tmp.newFile("rot.log")
        file.writeText("old\n")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("old"), tailer.poll())
        file.delete()
        assertTrue(tailer.poll().isEmpty())
        file.writeText("rotated\n")
        assertEquals(listOf("rotated"), tailer.poll())
    }

    @Test
    fun partial_final_line_then_drain() {
        val file = tmp.newFile("partial.log")
        file.writeText("complete\npartial")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("complete"), tailer.poll())
        assertEquals(listOf("partial"), tailer.drain())
    }

    @Test
    fun crlf_and_lf() {
        val file = tmp.newFile("mix.log")
        file.writeText("a\r\nb\nc\r\n")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("a", "b", "c"), tailer.poll())
    }

    @Test
    fun utf8_split_across_reads() {
        val file = tmp.newFile("utf8.log")
        val text = "café\n"
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("café"), tailer.poll())
    }

    @Test
    fun long_line_is_bounded() {
        val file = tmp.newFile("long.log")
        file.writeText("x".repeat(40_000) + "\n")
        val tailer = LogFileTailer(file, startAtEnd = false, maxLineBytes = 1024)
        val lines = tailer.poll()
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length <= 1024 })
    }

    @Test
    fun start_at_end_skips_history() {
        val file = tmp.newFile("hist.log")
        file.writeText("old\n")
        val tailer = LogFileTailer(file, startAtEnd = true)
        assertTrue(tailer.poll().isEmpty())
        file.appendText("new\n")
        assertEquals(listOf("new"), tailer.poll())
    }

    @Test
    fun copyTruncateRegrowIsNewGeneration() {
        val file = tmp.newFile("rotate.log")
        file.writeText("one\ntwo\n")
        val tailer = LogFileTailer(file, startAtEnd = false)
        assertEquals(listOf("one", "two"), tailer.poll())
        file.writeText("three\n")
        assertEquals(listOf("three"), tailer.poll())
        file.appendText("four\n")
        assertEquals(listOf("four"), tailer.poll())
    }
}
