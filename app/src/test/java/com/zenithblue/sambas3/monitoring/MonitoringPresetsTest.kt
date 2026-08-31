package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringPresetsTest {
    @Test
    fun presets_are_templates_and_mutation_becomes_custom() {
        assertEquals(MonitoringPreset.Minimal, MonitoringPresets.presetFor(MonitoringPresets.minimal))
        assertEquals(MonitoringPreset.Performance, MonitoringPresets.presetFor(MonitoringPresets.performance))
        assertEquals(MonitoringPreset.Developer, MonitoringPresets.presetFor(MonitoringPresets.developer))
        val changed = MonitoringPresets.performance - MonitoringMetric.PpuCpu
        assertEquals(MonitoringPreset.Custom, MonitoringPresets.presetFor(changed))
        assertNotEquals(MonitoringPresets.performance, changed)
    }

    @Test
    fun every_metric_has_one_descriptor() {
        assertEquals(MonitoringMetric.entries.size, MonitoringMetricDescriptors.all.size)
        assertTrue(MonitoringMetric.entries.all { MonitoringMetricDescriptors.descriptor(it).title.isNotBlank() })
    }
}
