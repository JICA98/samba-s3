package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerformanceMetricsMergerTest {

    private fun coreEmpty() = EmulatorMetrics(
        timestampNs = 1_000_000_000L,
        presentedFrameCount = 0L,
        fpsSource = "emu_flip",
        fps = null,
        frameTimeMs = null,
        ppuCpuPercent = 83.4f,
    )

    private fun fallbackFresh() = EmulatorMetrics(
        timestampNs = 2_000_000_000L,
        presentedFrameCount = 120L,
        fpsSource = "emu_flip",
        fps = 59.9f,
        frameTimeMs = 16.7f,
        fpsSamples = listOf(59.8f, 59.9f),
        frameTimeSamples = listOf(16.7f, 16.8f),
        fpsTimedSamples = listOf(TimedSample(1_900_000L, 59.8f), TimedSample(2_000_000L, 59.9f)),
        frameTimeTimedSamples = listOf(TimedSample(1_900_000L, 16.7f), TimedSample(2_000_000L, 16.8f)),
        ppuCpuPercent = 10f,
    )

    @Test
    fun `empty core frames fall back to surface measurement`() {
        val (merged, usedFallback) = PerformanceMetricsMerger.merge(coreEmpty(), fallbackFresh())

        assertTrue(usedFallback)
        assertEquals(59.9f, merged.fps)
        assertEquals(16.7f, merged.frameTimeMs)
        assertEquals(listOf(59.8f, 59.9f), merged.fpsSamples)
        assertEquals(2, merged.fpsTimedSamples.size)
        assertEquals(2, merged.frameTimeTimedSamples.size)
        assertEquals(120L, merged.presentedFrameCount)
        // Core CPU fields always win; the fallback's coarse proc estimates never override them.
        assertEquals(83.4f, merged.ppuCpuPercent)
        // Core timestamp is kept when valid.
        assertEquals(1_000_000_000L, merged.timestampNs)
    }

    @Test
    fun `zero core fps falls back to surface measurement`() {
        val core = coreEmpty().copy(fps = 0f, frameTimeMs = 0f)
        val (merged, usedFallback) = PerformanceMetricsMerger.merge(core, fallbackFresh())
        assertTrue(usedFallback)
        assertEquals(59.9f, merged.fps)
        assertEquals(16.7f, merged.frameTimeMs)
    }

    @Test
    fun `populated core frames are never replaced`() {
        val core = coreEmpty().copy(
            fps = 30f,
            frameTimeMs = 33.3f,
            fpsSamples = listOf(30f),
            frameTimeSamples = listOf(33.3f),
            fpsTimedSamples = listOf(TimedSample(900_000L, 30f)),
            frameTimeTimedSamples = listOf(TimedSample(900_000L, 33.3f)),
            presentedFrameCount = 50L,
        )
        val (merged, usedFallback) = PerformanceMetricsMerger.merge(core, fallbackFresh())

        assertFalse(usedFallback)
        assertEquals(30f, merged.fps)
        assertEquals(33.3f, merged.frameTimeMs)
        assertEquals(listOf(30f), merged.fpsSamples)
        assertEquals(50L, merged.presentedFrameCount)
    }

    @Test
    fun `stale fallback never fabricates frames`() {
        val staleFallback = fallbackFresh().copy(
            fps = null,
            frameTimeMs = null,
            fpsSamples = emptyList(),
            frameTimeSamples = emptyList(),
            fpsTimedSamples = emptyList(),
            frameTimeTimedSamples = emptyList(),
            presentedFrameCount = 0L,
        )
        val (merged, usedFallback) = PerformanceMetricsMerger.merge(coreEmpty(), staleFallback)

        assertNull(merged.fps)
        assertNull(merged.frameTimeMs)
        assertTrue(merged.fpsSamples.isEmpty())
        // Presented count from a stale fallback must not leak in either.
        assertEquals(0L, merged.presentedFrameCount)
        assertFalse(usedFallback)
    }

    @Test
    fun `fallback JSON parses through the shared parser with freshness gate`() {
        val parsed = PerformanceMetricsParser.parse(
            """{"version":2,"timestampUs":2000000,"presentedFrameCount":120,"frameSampleFresh":true,"fpsSource":"emu_flip","fps":59.9,"frametimeMs":16.7,"fpsSamples":[{"timestampUs":1900000,"value":59.8}],"frametimeSamples":[]}"""
        )
        assertNotNull(parsed)
        assertEquals(59.9f, parsed!!.metrics.fps)
        assertEquals(1, parsed.metrics.fpsTimedSamples.size)
    }

    @Test
    fun `stale fallback JSON parses to null frame values`() {
        val parsed = PerformanceMetricsParser.parse(
            """{"version":2,"timestampUs":2000000,"presentedFrameCount":120,"frameSampleFresh":false,"fpsSource":"emu_flip","fpsSamples":[],"frametimeSamples":[]}"""
        )
        assertNotNull(parsed)
        assertNull(parsed!!.metrics.fps)
        assertNull(parsed.metrics.frameTimeMs)
    }
}
