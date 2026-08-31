package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationMetricsTruthTest {
    @Test
    fun fps_uses_presented_frames_not_vblank() {
        // A 30 FPS renderer can still have 60 Hz VBlank activity.
        assertEquals(30f, PresentationMetricsMath.fpsFromPresentedFrames(30, 1_000_000L)!!, .001f)
    }

    @Test
    fun frame_time_uses_per_present_intervals_and_keeps_spikes() {
        val intervals = listOf(16f, 17f, 18f, 52f, 16f)
        assertEquals(23.8f, PresentationMetricsMath.averageFrameTimeMs(intervals)!!, .001f)
        assertTrue(intervals.contains(52f))
    }

    @Test
    fun no_presented_frame_means_no_fps_sample() {
        assertNull(PresentationMetricsMath.fpsFromPresentedFrames(0, 1_000_000L))
    }
}
