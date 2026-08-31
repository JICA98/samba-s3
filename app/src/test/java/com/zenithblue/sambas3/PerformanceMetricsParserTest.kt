package com.zenithblue.sambas3

import com.zenithblue.sambas3.monitoring.PerformanceMetricsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerformanceMetricsParserTest {
    @Test
    fun missing_optional_fields_are_allowed() {
        val parsed = PerformanceMetricsParser.parse("{\"version\":1}")
        assertTrue(parsed != null)
        assertNull(parsed!!.metrics.fps)
        assertTrue(parsed.metrics.fpsSamples.isEmpty())
    }

    @Test
    fun invalid_negative_and_non_finite_values_are_dropped() {
        val parsed = PerformanceMetricsParser.parse(
            "{\"fps\":-1,\"frametimeMs\":null,\"ppuThreads\":-3,\"rsxLoad\":82," +
                "\"fpsSamples\":[-1,60,null]}"
        )!!
        assertNull(parsed.metrics.fps)
        assertNull(parsed.metrics.frameTimeMs)
        assertNull(parsed.metrics.ppuThreads)
        assertEquals(82, parsed.metrics.rsxLoadPercent)
        assertEquals(listOf(60f), parsed.metrics.fpsSamples)
    }

    @Test
    fun oversized_history_is_bounded_to_latest_sixty_values() {
        val array = (0..100).joinToString(",")
        val samples = PerformanceMetricsParser.parse("{\"fpsSamples\":[$array]}")!!.metrics.fpsSamples
        assertEquals(60, samples.size)
        assertEquals(41f, samples.first())
        assertEquals(100f, samples.last())
    }

    @Test
    fun only_versioned_emu_flip_payload_can_supply_fps() {
        val old = PerformanceMetricsParser.parse("{\"version\":1,\"fps\":60,\"frametimeMs\":16.7}")!!
        assertNull(old.metrics.fps)
        val current = PerformanceMetricsParser.parse(
            "{\"version\":2,\"timestampUs\":1000,\"fpsSource\":\"emu_flip\",\"frameSampleFresh\":true," +
                "\"fps\":30,\"frametimeMs\":33.3,\"frametimeSamples\":[{" +
                "\"timestampUs\":900,\"value\":16},{\"timestampUs\":916,\"value\":52}]}"
        )!!
        assertEquals(30f, current.metrics.fps)
        assertEquals(listOf(16f, 52f), current.metrics.frameTimeTimedSamples.map { it.value })
        assertEquals(listOf(900L, 916L), current.metrics.frameTimeTimedSamples.map { it.timestampUs })
    }
}
