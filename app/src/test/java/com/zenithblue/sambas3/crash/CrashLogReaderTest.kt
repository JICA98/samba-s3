package com.zenithblue.sambas3.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class CrashLogReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun asciiControl() {
        val file = tmp.newFile("ascii.log")
        file.writeText("hello world")
        assertEquals(6L, CrashLogReader(file).find("world"))
    }

    @Test
    fun missingQueryIsMinusOne() {
        val file = tmp.newFile("miss.log")
        file.writeText("hello world")
        assertEquals(-1L, CrashLogReader(file).find("nope"))
    }

    @Test
    fun matchSpanning64KiBBoundary() {
        val file = tmp.newFile("span.log")
        val prefix = "x".repeat(65_532)
        file.writeText(prefix + "TAILMARK")
        assertEquals(65_532L, CrashLogReader(file).find("TAILMARK"))
    }

    @Test
    fun utf8ByteOffsetAfterMultibytePrefix() {
        val file = tmp.newFile("utf8.log")
        val prefix = "é💥न "
        file.writeBytes((prefix + "TARGET").toByteArray(Charsets.UTF_8))
        val expected = prefix.toByteArray(Charsets.UTF_8).size.toLong()
        assertEquals(expected, CrashLogReader(file).find("TARGET"))
    }

    @Test
    fun gzipDispatchYieldsOriginalText() {
        val raw = tmp.newFile("RPCSX.log.gz")
        GZIPOutputStream(raw.outputStream()).use { it.write("fatal marker near eof\n".toByteArray()) }
        val reader = CrashLogReader.forArtifact(raw)
        assertTrue(reader.read(0L).contains("fatal marker near eof"))
        assertEquals(0L, reader.find("fatal marker"))
    }

    @Test
    fun truncatedGzipIsPartial() {
        val raw = tmp.newFile("partial.log.gz")
        val full = java.io.ByteArrayOutputStream()
        GZIPOutputStream(full).use { it.write("complete payload that should survive\n".toByteArray()) }
        raw.writeBytes(full.toByteArray().copyOf(12))
        val reader = CrashLogReader.forArtifact(raw)
        reader.read(0L)
        assertTrue(reader.partial)
    }

    @Test
    fun tailDefaultReachesEofMarker() {
        val file = tmp.newFile("tail.log")
        file.writeBytes(ByteArray(300 * 1024) { 'a'.code.toByte() } + "EOF-MARKER".toByteArray())
        val reader = CrashLogReader(file)
        val (_, text) = reader.readTail(256 * 1024)
        assertTrue(text.contains("EOF-MARKER"))
    }
}
