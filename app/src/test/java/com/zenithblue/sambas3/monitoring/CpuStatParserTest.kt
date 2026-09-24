package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuStatParserTest {

    @Test
    fun testSystemCpu_standardLine_excludesGuestDoubleCounting() {
        // Line with user, nice, system, idle, iowait, irq, softirq, steal, guest, guest_nice
        // Fields:
        // cpu 100 20 30 500 50 10 5 5 40 10
        // user = 100
        // nice = 20
        // system = 30
        // idle = 500
        // iowait = 50
        // irq = 10
        // softirq = 5
        // steal = 5
        // total = 100 + 20 + 30 + 500 + 50 + 10 + 5 + 5 = 720 (guest=40 and guest_nice=10 NOT added)
        // idleAll = 500 + 50 = 550
        val line1 = "cpu  100 20 30 500 50 10 5 5 40 10"
        val snap1 = CpuStatParser.parseSystemCpuLine(line1)
        assertNotNull(snap1)
        assertEquals(720L, snap1!!.totalTicks)
        assertEquals(550L, snap1.idleTicks)

        // Line 2:
        // user increases by 50 (from 100 to 150)
        // nice increases by 0
        // system increases by 20 (from 30 to 50)
        // idle increases by 100 (from 500 to 600)
        // iowait increases by 10 (from 50 to 60)
        // irq increases by 5 (from 10 to 15)
        // softirq increases by 5 (from 5 to 10)
        // steal increases by 0
        // deltaTotal = 50 + 20 + 100 + 10 + 5 + 5 = 190
        // deltaIdle = 100 + 10 = 110
        // deltaBusy = 190 - 110 = 80
        // utilization = 80 / 190 * 100 = 42.10526%
        val line2 = "cpu  150 20 50 600 60 15 10 5 60 10"
        val snap2 = CpuStatParser.parseSystemCpuLine(line2)
        assertNotNull(snap2)
        assertEquals(720L + 190L, snap2!!.totalTicks)
        assertEquals(550L + 110L, snap2.idleTicks)

        val util = CpuStatParser.computeSystemUtilization(snap2, snap1)
        assertNotNull(util)
        assertEquals((80.0 / 190.0 * 100.0).toFloat(), util!!, 0.01f)
    }

    @Test
    fun testSystemCpu_zeroDeltaTotal_returnsNull() {
        val line = "cpu  100 20 30 500 50 10 5 5"
        val snap1 = CpuStatParser.parseSystemCpuLine(line)!!
        val snap2 = CpuStatParser.parseSystemCpuLine(line)!!
        assertNull(CpuStatParser.computeSystemUtilization(snap2, snap1))
    }

    @Test
    fun testSystemCpu_idleFullUtilization() {
        // Delta total = 100, delta idle = 0 -> 100%
        val snap1 = CpuStatParser.SystemCpuSnapshot(1000L, 500L)
        val snap2 = CpuStatParser.SystemCpuSnapshot(1100L, 500L)
        val util = CpuStatParser.computeSystemUtilization(snap2, snap1)
        assertNotNull(util)
        assertEquals(100f, util!!, 0.001f)
    }

    @Test
    fun testSystemCpu_idleZeroUtilization() {
        // Delta total = 100, delta idle = 100 -> 0%
        val snap1 = CpuStatParser.SystemCpuSnapshot(1000L, 500L)
        val snap2 = CpuStatParser.SystemCpuSnapshot(1100L, 600L)
        val util = CpuStatParser.computeSystemUtilization(snap2, snap1)
        assertNotNull(util)
        assertEquals(0f, util!!, 0.001f)
    }

    @Test
    fun testProcessCpu_multicoreEquivalentScale() {
        // 8-core device: 2 cores fully busy = 200.0%
        // clockTicksPerSecond = 100 Hz.
        // elapsed = 1.0 second.
        // 200 ticks across 1 second at 100 Hz = 2.0 core equivalents = 200.0%
        val util = CpuStatParser.computeProcessUtilization(
            currentTicks = 300L,
            previousTicks = 100L,
            elapsedSec = 1.0,
            clockTicksPerSecond = 100L
        )
        assertNotNull(util)
        assertEquals(200.0f, util!!, 0.01f)

        // 8 cores fully busy = 800.0%
        // 800 ticks across 1 second at 100 Hz = 8.0 core equivalents = 800.0%
        val util800 = CpuStatParser.computeProcessUtilization(
            currentTicks = 900L,
            previousTicks = 100L,
            elapsedSec = 1.0,
            clockTicksPerSecond = 100L
        )
        assertNotNull(util800)
        assertEquals(800.0f, util800!!, 0.01f)
    }

    @Test
    fun testProcessCpu_negativeDeltaTicks_returnsNull() {
        val util = CpuStatParser.computeProcessUtilization(
            currentTicks = 50L,
            previousTicks = 100L,
            elapsedSec = 1.0,
            clockTicksPerSecond = 100L
        )
        assertNull(util)
    }

    @Test
    fun testProcessCpu_zeroOrNegativeElapsed_returnsNull() {
        assertNull(CpuStatParser.computeProcessUtilization(200L, 100L, 0.0, 100L))
        assertNull(CpuStatParser.computeProcessUtilization(200L, 100L, -0.5, 100L))
    }

    @Test
    fun testParseProcessTicks_extractsUtimeAndStime() {
        // Standard /proc/self/stat example:
        // pid (process_name with spaces) S ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime
        // utime is field 11 after `)` (0-indexed), stime is field 12
        val sampleStat = "12345 (com.zenithblue.sambas3) S 1 12345 0 0 -1 4194560 100 0 0 0 450 150 0 0 20 0 10 0"
        val ticks = CpuStatParser.parseProcessTicks(sampleStat)
        assertNotNull(ticks)
        assertEquals(450L + 150L, ticks!!)
    }
}
