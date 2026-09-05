package com.zenithblue.sambas3.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitoringOverlaySettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("monitoring_overlay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `settings round-trip test - every field survives write and read`() {
        val testSettings = MonitoringSettings(
            enabled = true,
            enabledMetrics = setOf(
                MonitoringMetric.Fps,
                MonitoringMetric.PpuCpu,
                MonitoringMetric.GpuFrequency,
                MonitoringMetric.RamUsed,
                MonitoringMetric.BatteryTemperature
            ),
            graphMetrics = setOf(MonitoringMetric.Fps, MonitoringMetric.FrameTime),
            position = MonitoringPosition.BottomRight,
            layout = MonitoringLayout.Detailed,
            updateMs = 750L,
            opacity = 0.45f,
            textScale = 1.15f,
            graphHistorySeconds = 20,
            fpsScaleMode = FpsGraphScale.ZeroBased,
            hideWithMenu = false
        )

        MonitoringOverlaySettings.write(context, testSettings)
        val readBack = MonitoringOverlaySettings.read(context)

        assertEquals(testSettings.enabled, readBack.enabled)
        assertEquals(testSettings.enabledMetrics, readBack.enabledMetrics)
        assertEquals(testSettings.graphMetrics, readBack.graphMetrics)
        assertEquals(testSettings.position, readBack.position)
        assertEquals(testSettings.layout, readBack.layout)
        assertEquals(testSettings.updateMs, readBack.updateMs)
        assertEquals(testSettings.opacity, readBack.opacity, 0.001f)
        assertEquals(testSettings.textScale, readBack.textScale, 0.001f)
        assertEquals(testSettings.graphHistorySeconds, readBack.graphHistorySeconds)
        assertEquals(testSettings.fpsScaleMode, readBack.fpsScaleMode)
        assertEquals(testSettings.hideWithMenu, readBack.hideWithMenu)
    }

    @Test
    fun `write emits state to StateFlow observers`() {
        val flow = MonitoringOverlaySettings.state(context)

        val updated = MonitoringSettings(
            enabled = true,
            enabledMetrics = setOf(MonitoringMetric.Fps),
            position = MonitoringPosition.TopCenter,
            layout = MonitoringLayout.Grid
        )

        MonitoringOverlaySettings.write(context, updated)

        assertEquals(updated, flow.value)
    }

    @Test
    fun `empty enabled metrics survives round trip without falling back to preset`() {
        val emptyMetricsSettings = MonitoringSettings(
            enabled = true,
            enabledMetrics = emptySet(),
            graphMetrics = emptySet()
        )

        MonitoringOverlaySettings.write(context, emptyMetricsSettings)
        val readBack = MonitoringOverlaySettings.read(context)

        assertTrue(readBack.enabledMetrics.isEmpty())
        assertTrue(readBack.graphMetrics.isEmpty())
    }

    @Test
    fun `clamping behaves correctly for numeric fields`() {
        val outOfBounds = MonitoringSettings(
            updateMs = 50L,      // Min is 250
            opacity = 2.0f,      // Max is 1.0
            textScale = 0.5f,    // Min is 0.75
            graphHistorySeconds = 60 // Max is 30
        )

        MonitoringOverlaySettings.write(context, outOfBounds)
        val readBack = MonitoringOverlaySettings.read(context)

        assertEquals(250L, readBack.updateMs)
        assertEquals(1.0f, readBack.opacity, 0.001f)
        assertEquals(0.75f, readBack.textScale, 0.001f)
        assertEquals(30, readBack.graphHistorySeconds)
    }
}
