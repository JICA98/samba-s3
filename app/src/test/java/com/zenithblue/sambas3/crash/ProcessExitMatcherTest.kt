package com.zenithblue.sambas3.crash

import com.zenithblue.sambas3.session.EmulationSessionRecord
import com.zenithblue.sambas3.session.EmulationSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitMatcherTest {

    private val sessionStart = 1727220000000L // T0
    private val sessionHeartbeat = 1727220060000L // T0 + 60s

    private fun createSession(
        pid: Int = 5454,
        sessionId: String = "session-1",
        startedAt: Long = sessionStart,
        lastHeartbeat: Long = sessionHeartbeat,
    ) = EmulationSessionRecord(
        sessionId = sessionId,
        gamePath = "/game/path",
        titleId = "BCUS98123",
        gameName = "God of War III",
        startedAtMs = startedAt,
        lastHeartbeatMs = lastHeartbeat,
        state = EmulationSessionState.RUNNING,
        activityInstanceId = 1L,
        surfaceGeneration = 1L,
        driverLabel = null,
        cleanTermination = false,
        pidAtSessionStart = pid,
    )

    @Test
    fun matchesExactPidAndValidTimestamp() {
        val session = createSession(pid = 5454)
        val records = listOf(
            ProcessExitRecord(
                pid = 5454,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_CRASH_NATIVE,
                reasonName = "CRASH_NATIVE",
                status = 6,
                timestamp = sessionStart + 45000L,
            ),
            ProcessExitRecord(
                pid = 10950,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_PACKAGE_UPDATED,
                reasonName = "PACKAGE_UPDATED",
                status = 0,
                timestamp = sessionStart + 90000L,
            ),
        )

        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNotNull(matched)
        assertEquals(5454, matched?.pid)
        assertEquals(ProcessExitRecord.REASON_CRASH_NATIVE, matched?.reason)
    }

    @Test
    fun rejectsRecycledPidFromEarlierInvocation() {
        val session = createSession(pid = 5454, startedAt = sessionStart)
        // Record has same PID 5454, but died 10 minutes before session began!
        val records = listOf(
            ProcessExitRecord(
                pid = 5454,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_LOW_MEMORY,
                reasonName = "LOW_MEMORY",
                status = 0,
                timestamp = sessionStart - 600000L, // 10 minutes before session
            )
        )

        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNull("Stale exit record before session start must be rejected", matched)
    }

    @Test
    fun neverTakesLatestRecordWhenPidDoesNotMatch() {
        // Active session is PID 5454
        val session = createSession(pid = 5454)
        // Exit info history contains other PIDs (package update, low memory, etc.) but NO record for 5454
        val records = listOf(
            ProcessExitRecord(
                pid = 10950,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_PACKAGE_UPDATED,
                reasonName = "PACKAGE_UPDATED",
                status = 0,
                timestamp = sessionStart + 100000L,
            ),
            ProcessExitRecord(
                pid = 4189,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_LOW_MEMORY,
                reasonName = "LOW_MEMORY",
                status = 0,
                timestamp = sessionStart + 80000L,
            ),
        )

        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNull("Matcher must return null instead of picking wrong PID", matched)
    }

    @Test
    fun distinguishesPpuCompileWorkerFromPrimaryProcess() {
        val session = createSession(pid = 5586)
        val records = listOf(
            // Process is :ppu_compile
            ProcessExitRecord(
                pid = 5586,
                processName = "com.zenithblue.sambas3:ppu_compile",
                reason = ProcessExitRecord.REASON_SIGNALED,
                reasonName = "SIGNALED",
                status = 9,
                timestamp = sessionStart + 20000L,
            ),
            // Process is main emulator
            ProcessExitRecord(
                pid = 4475,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_SIGNALED,
                reasonName = "SIGNALED",
                status = 6,
                timestamp = sessionStart + 25000L,
            ),
        )

        // Matching main emulator session against records must ignore :ppu_compile
        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNull("ppu_compile worker exit must not match primary emulator session", matched)

        // findPpuCompileExits should identify the ppu worker exit
        val ppuExits = ProcessExitMatcher.findPpuCompileExits(records)
        assertEquals(1, ppuExits.size)
        assertEquals(5586, ppuExits[0].pid)
        assertTrue(ppuExits[0].isPpuCompile)
    }

    @Test
    fun matchesBySessionIdMarkerWhenPidUnset() {
        val session = createSession(pid = 0, sessionId = "unique-session-xyz")
        val records = listOf(
            ProcessExitRecord(
                pid = 9999,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_SIGNALED,
                reasonName = "SIGNALED",
                status = 11,
                timestamp = sessionStart + 10000L,
                description = "fatal crash in session unique-session-xyz",
            )
        )

        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNotNull(matched)
        assertEquals(9999, matched?.pid)
    }

    @Test
    fun acceptsTimestampWithinClockSkewTolerance() {
        val session = createSession(pid = 5454, startedAt = sessionStart)
        // Exit timestamp is 2s before session recorded startedAt (within 5s tolerance)
        val records = listOf(
            ProcessExitRecord(
                pid = 5454,
                processName = "com.zenithblue.sambas3",
                reason = ProcessExitRecord.REASON_CRASH_NATIVE,
                reasonName = "CRASH_NATIVE",
                status = 11,
                timestamp = sessionStart - 2000L,
            )
        )

        val matched = ProcessExitMatcher.matchSession(session, records)
        assertNotNull("Should accept within 5s clock tolerance", matched)
        assertEquals(5454, matched?.pid)
    }
}
