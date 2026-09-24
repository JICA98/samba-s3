package com.zenithblue.sambas3.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitInfoCollectorTest {

    private val sampleDumpsys = """
        ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
        Last Timestamp of Persistence Into Persistent Storage: 2026-09-25 02:39:26.765
          package: com.zenithblue.sambas3
            Historical Process Exit for uid=10463
                ApplicationExitInfo #0:
                  timestamp=2026-09-25 02:41:35.649 pid=10950 realUid=10463 packageUid=10463 definingUid=10463 user=0
                  process=com.zenithblue.sambas3 reason=16 (PACKAGE UPDATED) subreason=0 (UNKNOWN) status=0
                  importance=400 pss=0.00 rss=270MB description=stop com.zenithblue.sambas3 due to installPackageLI state=empty trace=null
                ApplicationExitInfo #1:
                  timestamp=2026-09-25 00:33:03.880 pid=4189 realUid=10463 packageUid=10463 definingUid=10463 user=0
                  process=com.zenithblue.sambas3 reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=0
                  importance=100 pss=0.00 rss=2.5GB description=null state=empty trace=null
                ApplicationExitInfo #2:
                  timestamp=2026-09-24 23:26:31.481 pid=4475 realUid=10463 packageUid=10463 definingUid=10463 user=0
                  process=com.zenithblue.sambas3 reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=6
                  importance=100 pss=0.00 rss=1.9GB description=null state=empty trace=Fatal signal 6 (SIGABRT)
                ApplicationExitInfo #3:
                  timestamp=2026-09-24 23:24:02.592 pid=5586 realUid=10463 packageUid=10463 definingUid=10463 user=0
                  process=com.zenithblue.sambas3:ppu_compile reason=2 (SIGNALED) subreason=0 (UNKNOWN) status=9
                  importance=100 pss=0.00 rss=0.00 description=null state=empty trace=null
    """.trimIndent()

    @Test
    fun parsesDumpsysTextSuccessfully() {
        val records = ProcessExitInfoCollector.parseDumpsysText(sampleDumpsys)
        assertEquals(4, records.size)

        // Record 0: Package updated
        val r0 = records[0]
        assertEquals(10950, r0.pid)
        assertEquals("com.zenithblue.sambas3", r0.processName)
        assertEquals(16, r0.reason)
        assertEquals("PACKAGE UPDATED", r0.reasonName)
        assertEquals(0, r0.status)
        assertEquals(400, r0.importance)
        assertEquals(270L * 1024 * 1024, r0.rssBytes)
        assertEquals("stop com.zenithblue.sambas3 due to installPackageLI", r0.description)
        assertNull(r0.trace)
        assertFalse(r0.isPpuCompile)
        assertTrue(r0.isPrimaryProcess)

        // Record 1: Low memory
        val r1 = records[1]
        assertEquals(4189, r1.pid)
        assertEquals(3, r1.reason)
        assertEquals("LOW_MEMORY", r1.reasonName)
        assertEquals((2.5 * 1024 * 1024 * 1024).toLong(), r1.rssBytes)
        assertNull(r1.description)

        // Record 2: Signaled (SIGABRT) with trace
        val r2 = records[2]
        assertEquals(4475, r2.pid)
        assertEquals(2, r2.reason)
        assertEquals(6, r2.status)
        assertEquals("SIGABRT", r2.signalName)
        assertEquals((1.9 * 1024 * 1024 * 1024).toLong(), r2.rssBytes)
        assertNotNull(r2.trace)
        assertTrue(r2.trace!!.contains("Fatal signal 6 (SIGABRT)"))

        // Record 3: ppu_compile
        val r3 = records[3]
        assertEquals(5586, r3.pid)
        assertEquals("com.zenithblue.sambas3:ppu_compile", r3.processName)
        assertTrue(r3.isPpuCompile)
        assertFalse(r3.isPrimaryProcess)
        assertEquals(9, r3.status)
        assertEquals("SIGKILL", r3.signalName)
    }

    @Test
    fun parseBytesConvertsVariousUnits() {
        assertEquals(0L, ProcessExitInfoCollector.parseBytes(null))
        assertEquals(0L, ProcessExitInfoCollector.parseBytes("0.00"))
        assertEquals(0L, ProcessExitInfoCollector.parseBytes("0"))
        assertEquals(1024L, ProcessExitInfoCollector.parseBytes("1KB"))
        assertEquals(50L * 1024 * 1024, ProcessExitInfoCollector.parseBytes("50MB"))
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), ProcessExitInfoCollector.parseBytes("1.5GB"))
        assertEquals(512L, ProcessExitInfoCollector.parseBytes("512B"))
        assertEquals(12345L, ProcessExitInfoCollector.parseBytes("12345"))
    }
}
