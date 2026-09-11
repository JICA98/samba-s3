package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoFrameWatchdogTest {
    @Test
    fun unchanged_counter_times_out_at_limit() {
        val armed = NoFrameWatchdog.observe(
            NoFrameWatchdog.State(),
            nowMs = 1_000L,
            shouldWatch = true,
            presentedFrameCount = 40L,
            timeoutMs = 120_000L,
        )
        val before = NoFrameWatchdog.observe(
            armed.state,
            nowMs = 120_999L,
            shouldWatch = true,
            presentedFrameCount = 40L,
            timeoutMs = 120_000L,
        )
        val expired = NoFrameWatchdog.observe(
            before.state,
            nowMs = 121_000L,
            shouldWatch = true,
            presentedFrameCount = 40L,
            timeoutMs = 120_000L,
        )

        assertFalse(before.timedOut)
        assertTrue(expired.timedOut)
        assertEquals(120_000L, expired.stalledForMs)
    }

    @Test
    fun frame_progress_restarts_timeout_window() {
        val initial = NoFrameWatchdog.observe(
            NoFrameWatchdog.State(), 1_000L, true, 40L, 120_000L
        )
        val advanced = NoFrameWatchdog.observe(
            initial.state, 100_000L, true, 41L, 120_000L
        )
        val later = NoFrameWatchdog.observe(
            advanced.state, 180_000L, true, 41L, 120_000L
        )

        assertFalse(later.timedOut)
        assertEquals(100_000L, later.state.lastFrameProgressAtMs)
    }

    @Test
    fun pause_or_unavailable_counter_disarms_watchdog() {
        val armed = NoFrameWatchdog.State(40L, 1_000L)
        val paused = NoFrameWatchdog.observe(armed, 200_000L, false, 40L, 120_000L)
        val unavailable = NoFrameWatchdog.observe(armed, 200_000L, true, null, 120_000L)

        assertFalse(paused.timedOut)
        assertNull(paused.state.presentedFrameCount)
        assertFalse(unavailable.timedOut)
        assertNull(unavailable.state.lastFrameProgressAtMs)
    }

    @Test
    fun counter_reset_starts_a_new_window() {
        val reset = NoFrameWatchdog.observe(
            NoFrameWatchdog.State(400L, 1_000L),
            nowMs = 200_000L,
            shouldWatch = true,
            presentedFrameCount = 0L,
            timeoutMs = 120_000L,
        )

        assertFalse(reset.timedOut)
        assertEquals(0L, reset.state.presentedFrameCount)
        assertEquals(200_000L, reset.state.lastFrameProgressAtMs)
    }
}
