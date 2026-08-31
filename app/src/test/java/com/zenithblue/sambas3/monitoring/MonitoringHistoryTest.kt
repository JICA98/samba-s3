package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringHistoryTest {
    @Test
    fun duplicate_timestamp_is_ignored_and_window_is_time_based() {
        val history = MonitoringHistory()
        val settings = setOf(MonitoringMetric.Fps, MonitoringMetric.FrameTime)
        history.append(EmulatorMetrics(timestampNs = 1_000_000_000L, fps = 60f, frameTimeMs = 16.7f), 5, settings)
        history.append(EmulatorMetrics(timestampNs = 1_000_000_000L, fps = 45f, frameTimeMs = 33.3f), 5, settings)
        history.append(EmulatorMetrics(timestampNs = 7_000_000_000L, fps = 59f, frameTimeMs = 16.9f), 5, settings)
        assertEquals(listOf(59f), history.fps().map { it.value })
        assertEquals(listOf(16.9f), history.frameTime().map { it.value })
    }

    @Test
    fun disabled_graph_metrics_do_not_store_samples_and_generation_resets() {
        val history = MonitoringHistory()
        history.append(EmulatorMetrics(timestampNs = 1_000_000_000L, fps = 60f), 10, emptySet())
        assertTrue(history.fps().isEmpty())
        history.append(EmulatorMetrics(timestampNs = 2_000_000_000L, fps = 60f), 10, setOf(MonitoringMetric.Fps), 1L)
        history.append(EmulatorMetrics(timestampNs = 3_000_000_000L, fps = 59f), 10, setOf(MonitoringMetric.Fps), 2L)
        assertEquals(listOf(59f), history.fps().map { it.value })
    }

    @Test
    fun per_frame_timestamps_are_preserved_for_frametime_graph() {
        val history = MonitoringHistory()
        history.append(
            EmulatorMetrics(
                timestampNs = 1_000_000_000L,
                frameTimeMs = 23.8f,
                frameTimeTimedSamples = listOf(
                    TimedSample(900_000L, 16f), TimedSample(916_000L, 17f),
                    TimedSample(933_000L, 52f)
                )
            ),
            10,
            setOf(MonitoringMetric.FrameTime)
        )
        assertEquals(listOf(16f, 17f, 52f), history.frameTime().map { it.value })
        assertEquals(listOf(900_000L, 916_000L, 933_000L), history.frameTime().map { it.timestampUs })
    }

    @Test
    fun repeated_native_ring_snapshot_does_not_reverse_or_duplicate_graph_path() {
        val history = MonitoringHistory()
        val samples = listOf(
            TimedSample(1_000_000L, 60f),
            TimedSample(2_000_000L, 59f),
            TimedSample(3_000_000L, 58f)
        )
        val settings = setOf(MonitoringMetric.Fps)
        history.append(EmulatorMetrics(timestampNs = 3_000_000_000L, fpsTimedSamples = samples), 10, settings)
        history.append(EmulatorMetrics(timestampNs = 4_000_000_000L, fpsTimedSamples = samples + TimedSample(4_000_000L, 57f)), 10, settings)
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L), history.fps().map { it.timestampUs })
        assertEquals(listOf(60f, 59f, 58f, 57f), history.fps().map { it.value })
    }
}
