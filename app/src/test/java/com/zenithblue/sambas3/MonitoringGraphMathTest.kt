package com.zenithblue.sambas3

import com.zenithblue.sambas3.monitoring.MonitoringGraphMath
import com.zenithblue.sambas3.monitoring.GraphScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringGraphMathTest {
    @Test
    fun known_fps_series_is_monotonic_and_zero_based() {
        val scale = MonitoringGraphMath.autoScale(listOf(30f, 40f, 50f, 60f), fallbackUpper = 60f)
        val points = MonitoringGraphMath.mapToUnit(listOf(30f, 40f, 50f, 60f), scale)
        assertEquals(4, points.size)
        assertTrue(points.zipWithNext().all { it.first < it.second })
        assertEquals(0f, scale.lower)
        assertTrue(scale.upper >= 60f)
    }

    @Test
    fun invalid_and_constant_series_are_safe() {
        val values = MonitoringGraphMath.validSamples(listOf(Float.NaN, -1f, Float.POSITIVE_INFINITY, 5f, 5f))
        assertEquals(listOf(5f, 5f), values)
        val points = MonitoringGraphMath.mapToUnit(values, GraphScale(0f, 30f))
        assertEquals(listOf(5f / 30f, 5f / 30f), points)
    }

    @Test
    fun frame_time_sequence_keeps_oldest_to_newest_order() {
        val points = MonitoringGraphMath.mapToUnit(
            listOf(33.3f, 25f, 20f, 16.7f),
            GraphScale(0f, 40f)
        )
        assertTrue(points.zipWithNext().all { it.first > it.second })
    }
}
