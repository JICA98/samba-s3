package com.zenithblue.sambas3.monitoring

import androidx.compose.ui.Alignment
import com.zenithblue.sambas3.ui.monitoring.monitoringAlignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringOverlayPresentationTest {

    @Test
    fun `every monitoring metric maps to exactly one predictable visible representation`() {
        assertEquals(28, MonitoringMetric.entries.size)
        assertEquals(28, MonitoringMetricDescriptors.all.size)

        val mappedMetrics = MonitoringMetricDescriptors.all.map { it.metric }.toSet()
        assertEquals(MonitoringMetric.entries.toSet(), mappedMetrics)

        // Verify each metric has a non-empty short label
        for (desc in MonitoringMetricDescriptors.all) {
            assertTrue("Descriptor short label must not be blank for ${desc.metric}", desc.shortLabel.isNotBlank())
        }
    }

    @Test
    fun `selected with value produces formatted row`() {
        val enabled = setOf(MonitoringMetric.Fps, MonitoringMetric.RamUsed)
        val emulator = EmulatorMetrics(fps = 59.7f)
        val android = AndroidSystemMetrics(ramUsedBytes = 4_200_000_000L)

        val entries = MonitoringOverlayPresentation.buildDisplayEntries(enabled, emulator, android)

        assertEquals(2, entries.size)
        assertEquals(MonitoringMetric.Fps, entries[0].metric)
        assertEquals("FPS", entries[0].label)
        assertEquals("59.7", entries[0].value)
        assertEquals("59.7", entries[0].displayValue)

        assertEquals(MonitoringMetric.RamUsed, entries[1].metric)
        assertEquals("RAM", entries[1].label)
        assertEquals("4.2G", entries[1].value)
        assertEquals("4.2G", entries[1].displayValue)
    }

    @Test
    fun `selected with null value produces row with truthful unavailable state`() {
        // Every single metric selected, but completely empty telemetry
        val enabled = MonitoringMetric.entries.toSet()
        val emptyEmulator = EmulatorMetrics()
        val emptyAndroid = AndroidSystemMetrics()

        val entries = MonitoringOverlayPresentation.buildDisplayEntries(enabled, emptyEmulator, emptyAndroid)

        // All 28 metrics must remain represented
        assertEquals(28, entries.size)
        for (entry in entries) {
            assertNull("Value should be null when telemetry is missing for ${entry.metric}", entry.value)
            assertEquals("Display value should be truthful unavailable '—' for ${entry.metric}", "—", entry.displayValue)
            assertTrue("Label must be present for ${entry.metric}", entry.label.isNotBlank())
        }
    }

    @Test
    fun `unselected metric is absent from output`() {
        val enabled = setOf(MonitoringMetric.Fps)
        val emulator = EmulatorMetrics(fps = 60f, hostCpuPercent = 50f)
        val android = AndroidSystemMetrics(systemCpuPercent = 20f)

        val entries = MonitoringOverlayPresentation.buildDisplayEntries(enabled, emulator, android)

        assertEquals(1, entries.size)
        assertEquals(MonitoringMetric.Fps, entries[0].metric)
        assertTrue(entries.none { it.metric == MonitoringMetric.RpcsxHostCpu })
        assertTrue(entries.none { it.metric == MonitoringMetric.AndroidSystemCpu })
    }

    @Test
    fun `output order is deterministic based on catalog order rather than set iteration`() {
        // Reverse order input
        val enabledReversed = linkedSetOf(
            MonitoringMetric.ThermalHeadroom,
            MonitoringMetric.RamUsed,
            MonitoringMetric.PpuCpu,
            MonitoringMetric.Fps
        )
        val entries = MonitoringOverlayPresentation.buildDisplayEntries(
            enabledReversed,
            EmulatorMetrics(fps = 60f, ppuCpuPercent = 10f),
            AndroidSystemMetrics(ramUsedBytes = 1_000_000_000L, thermalHeadroom = 1.0f)
        )

        val expectedOrder = listOf(
            MonitoringMetric.Fps,
            MonitoringMetric.PpuCpu,
            MonitoringMetric.RamUsed,
            MonitoringMetric.ThermalHeadroom
        )
        assertEquals(expectedOrder, entries.map { it.metric })
    }

    @Test
    fun `combined RAM, swap, and thread cases do not hide individually selected settings`() {
        // All thread metrics enabled individually
        val threadMetrics = setOf(
            MonitoringMetric.PpuThreads,
            MonitoringMetric.SpuThreads,
            MonitoringMetric.HostThreads
        )
        val threadEntries = MonitoringOverlayPresentation.buildDisplayEntries(
            threadMetrics,
            EmulatorMetrics(ppuThreads = 13, spuThreads = 0, hostThreads = 96),
            AndroidSystemMetrics()
        )
        assertEquals(3, threadEntries.size)
        assertEquals(listOf("PPU THR", "SPU THR", "HOST THR"), threadEntries.map { it.label })
        assertEquals(listOf("13", "0", "96"), threadEntries.map { it.displayValue })

        // RAM used and total both enabled individually
        val ramMetrics = setOf(MonitoringMetric.RamUsed, MonitoringMetric.RamTotal)
        val ramEntries = MonitoringOverlayPresentation.buildDisplayEntries(
            ramMetrics,
            EmulatorMetrics(),
            AndroidSystemMetrics(ramUsedBytes = 2_000_000_000L, ramTotalBytes = 8_000_000_000L)
        )
        assertEquals(2, ramEntries.size)
        assertEquals(listOf("RAM", "RAM TOTAL"), ramEntries.map { it.label })
        assertEquals(listOf("2.0G", "8.0G"), ramEntries.map { it.displayValue })

        // Swap used and total both enabled individually
        val swapMetrics = setOf(MonitoringMetric.SwapUsed, MonitoringMetric.SwapTotal)
        val swapEntries = MonitoringOverlayPresentation.buildDisplayEntries(
            swapMetrics,
            EmulatorMetrics(),
            AndroidSystemMetrics(swapUsedBytes = 500_000_000L, swapTotalBytes = 2_000_000_000L)
        )
        assertEquals(2, swapEntries.size)
        assertEquals(listOf("SWAP", "SWAP TOTAL"), swapEntries.map { it.label })
        assertEquals(listOf("500M", "2.0G"), swapEntries.map { it.displayValue })
    }

    @Test
    fun `graph state test - placeholder vs data model vs unselected`() {
        val emulator = EmulatorMetrics(fps = 60f, frameTimeMs = 16.6f)
        val emptyHistory = emptyList<TimedSample>()
        val oneSample = listOf(TimedSample(1_000L, 60f))
        val twoSamples = listOf(TimedSample(1_000L, 60f), TimedSample(2_000L, 59.8f))

        // 1. Selected graph + zero samples -> placeholder exists
        val zeroGraphs = MonitoringOverlayPresentation.buildGraphEntries(
            setOf(MonitoringMetric.Fps),
            emulator,
            emptyHistory,
            emptyHistory
        )
        assertEquals(1, zeroGraphs.size)
        assertTrue(zeroGraphs[0].isPlaceholder)
        assertEquals("FPS", zeroGraphs[0].label)
        assertEquals("60.0", zeroGraphs[0].currentValue)

        // 2. Selected graph + one sample -> placeholder exists
        val oneGraphs = MonitoringOverlayPresentation.buildGraphEntries(
            setOf(MonitoringMetric.Fps),
            emulator,
            oneSample,
            emptyHistory
        )
        assertEquals(1, oneGraphs.size)
        assertTrue(oneGraphs[0].isPlaceholder)

        // 3. Selected graph + two or more samples -> graph data model exists
        val twoGraphs = MonitoringOverlayPresentation.buildGraphEntries(
            setOf(MonitoringMetric.Fps),
            emulator,
            twoSamples,
            emptyHistory
        )
        assertEquals(1, twoGraphs.size)
        assertFalse(twoGraphs[0].isPlaceholder)
        assertEquals(2, twoGraphs[0].samples.size)

        // 4. Unselected graph -> graph absent
        val unselectedGraphs = MonitoringOverlayPresentation.buildGraphEntries(
            emptySet(),
            emulator,
            twoSamples,
            twoSamples
        )
        assertTrue(unselectedGraphs.isEmpty())

        // 5. Both graphs enabled
        val bothGraphs = MonitoringOverlayPresentation.buildGraphEntries(
            setOf(MonitoringMetric.Fps, MonitoringMetric.FrameTime),
            emulator,
            twoSamples,
            oneSample
        )
        assertEquals(2, bothGraphs.size)
        assertEquals("FPS", bothGraphs[0].label)
        assertFalse(bothGraphs[0].isPlaceholder)
        assertEquals("FRAME", bothGraphs[1].label)
        assertTrue(bothGraphs[1].isPlaceholder)
        assertEquals("16.6 ms", bothGraphs[1].currentValue)
    }

    @Test
    fun `position mapping test - all six enum values map to six correct Compose alignments`() {
        assertEquals(Alignment.TopStart, monitoringAlignment(MonitoringPosition.TopLeft))
        assertEquals(Alignment.TopCenter, monitoringAlignment(MonitoringPosition.TopCenter))
        assertEquals(Alignment.TopEnd, monitoringAlignment(MonitoringPosition.TopRight))
        assertEquals(Alignment.BottomStart, monitoringAlignment(MonitoringPosition.BottomLeft))
        assertEquals(Alignment.BottomCenter, monitoringAlignment(MonitoringPosition.BottomCenter))
        assertEquals(Alignment.BottomEnd, monitoringAlignment(MonitoringPosition.BottomRight))

        // Ensure all enum entries are covered and map to distinct alignments
        val allAlignments = MonitoringPosition.entries.map { monitoringAlignment(it) }.toSet()
        assertEquals(6, allAlignments.size)
    }
}
