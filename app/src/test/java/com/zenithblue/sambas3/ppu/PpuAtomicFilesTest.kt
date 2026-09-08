package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PpuAtomicFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeReplacesTargetAndKeepsLastGoodOnSecondWrite() {
        val target = tmp.newFile("session.json")
        assertTrue(PpuAtomicFiles.writeUtf8(target, "first"))
        assertEquals("first", target.readText())
        assertTrue(PpuAtomicFiles.writeUtf8(target, "second"))
        assertEquals("second", target.readText())
        val leftovers = target.parentFile!!.list()?.filter { it.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun writeCreatesParentDirectory() {
        val nested = File(tmp.root, "ppu-install/session.json")
        assertTrue(PpuAtomicFiles.writeUtf8(nested, "{\"ok\":true}"))
        assertEquals("{\"ok\":true}", nested.readText())
    }
}
