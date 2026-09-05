package com.zenithblue.sambas3.monitoring

import java.util.Locale

data class MonitoringDisplayEntry(
    val metric: MonitoringMetric,
    val label: String,
    val value: String?
) {
    val displayValue: String get() = value ?: "—"
}

data class MonitoringGraphEntry(
    val metric: MonitoringMetric,
    val label: String,
    val currentValue: String?,
    val isPlaceholder: Boolean,
    val samples: List<TimedSample>,
    val isFrameTime: Boolean
)

object MonitoringOverlayPresentation {

    fun buildDisplayEntries(
        enabledMetrics: Set<MonitoringMetric>,
        emulator: EmulatorMetrics,
        android: AndroidSystemMetrics
    ): List<MonitoringDisplayEntry> {
        return MonitoringMetricDescriptors.all
            .filter { it.metric in enabledMetrics }
            .map { desc ->
                val value = formatMetricValue(desc.metric, emulator, android)
                MonitoringDisplayEntry(
                    metric = desc.metric,
                    label = desc.shortLabel,
                    value = value
                )
            }
    }

    fun buildGraphEntries(
        graphMetrics: Set<MonitoringMetric>,
        emulator: EmulatorMetrics,
        fpsHistory: List<TimedSample>,
        frameTimeHistory: List<TimedSample>
    ): List<MonitoringGraphEntry> = buildList {
        if (MonitoringMetric.Fps in graphMetrics) {
            val current = emulator.fps?.let { String.format(Locale.US, "%.1f", it) }
            add(
                MonitoringGraphEntry(
                    metric = MonitoringMetric.Fps,
                    label = "FPS",
                    currentValue = current,
                    isPlaceholder = fpsHistory.size < 2,
                    samples = fpsHistory,
                    isFrameTime = false
                )
            )
        }
        if (MonitoringMetric.FrameTime in graphMetrics) {
            val current = emulator.frameTimeMs?.let { String.format(Locale.US, "%.1f ms", it) }
            add(
                MonitoringGraphEntry(
                    metric = MonitoringMetric.FrameTime,
                    label = "FRAME",
                    currentValue = current,
                    isPlaceholder = frameTimeHistory.size < 2,
                    samples = frameTimeHistory,
                    isFrameTime = true
                )
            )
        }
    }

    fun formatMetricValue(
        metric: MonitoringMetric,
        e: EmulatorMetrics,
        a: AndroidSystemMetrics
    ): String? = when (metric) {
        MonitoringMetric.Fps -> e.fps?.let { String.format(Locale.US, "%.1f", it) }
        MonitoringMetric.FrameTime -> e.frameTimeMs?.let { String.format(Locale.US, "%.1f ms", it) }
        MonitoringMetric.RpcsxHostCpu -> pct(e.hostCpuPercent)
        MonitoringMetric.PpuCpu -> pct(e.ppuCpuPercent)
        MonitoringMetric.SpuCpu -> pct(e.spuCpuPercent)
        MonitoringMetric.RsxCpu -> pct(e.rsxCpuPercent)
        MonitoringMetric.RsxLoad -> pct(e.rsxLoadPercent?.toFloat())
        MonitoringMetric.PpuThreads -> e.ppuThreads?.toString()
        MonitoringMetric.SpuThreads -> e.spuThreads?.toString()
        MonitoringMetric.HostThreads -> e.hostThreads?.toString()
        MonitoringMetric.AndroidSystemCpu -> pct(a.systemCpuPercent)
        MonitoringMetric.AndroidProcessCpu -> pct(a.processCpuPercent)
        MonitoringMetric.GpuHardwareLoad -> pct(a.gpu?.loadPercent?.toFloat())
        MonitoringMetric.GpuFrequency -> a.gpu?.frequencyHz?.let { "${it / 1_000_000} MHz" }
        MonitoringMetric.CpuFrequency -> a.cpuFrequenciesHz.maxOrNull()?.takeIf { it > 0 }?.let { "${it / 1_000_000} MHz" }
        MonitoringMetric.RamUsed -> bytes(a.ramUsedBytes)
        MonitoringMetric.RamAvailable -> bytes(a.ramAvailableBytes)
        MonitoringMetric.RamTotal -> bytes(a.ramTotalBytes)
        MonitoringMetric.AppRss -> bytes(a.processRssBytes)
        MonitoringMetric.AppPss -> bytes(a.processPssBytes)
        MonitoringMetric.SwapUsed -> bytes(a.swapUsedBytes)
        MonitoringMetric.SwapTotal -> bytes(a.swapTotalBytes)
        MonitoringMetric.ZramUsed -> bytes(a.zramUsedBytes)
        MonitoringMetric.BatteryPercent -> a.batteryPercent?.let { "$it%" }
        MonitoringMetric.BatteryTemperature -> a.batteryTemperatureC?.let { String.format(Locale.US, "%.1f°C", it) }
        MonitoringMetric.BatteryPower -> a.batteryPowerW?.let {
            val arrow = if (a.charging == true) "↑" else if (a.charging == false) "↓" else ""
            if (arrow.isNotEmpty()) String.format(Locale.US, "%.1fW %s", it, arrow)
            else String.format(Locale.US, "%.1fW", it)
        }
        MonitoringMetric.ThermalStatus -> a.thermalStatus?.let(::thermalLabel)
        MonitoringMetric.ThermalHeadroom -> a.thermalHeadroom?.let { String.format(Locale.US, "%.1f", it) }
    }

    private fun pct(value: Float?): String? = value?.let { String.format(Locale.US, "%.0f%%", it) }

    private fun bytes(value: Long?): String? = value?.let {
        if (it >= 1_000_000_000L) String.format(Locale.US, "%.1fG", it / 1_000_000_000f)
        else String.format(Locale.US, "%.0fM", it / 1_000_000f)
    }

    fun thermalLabel(status: Int): String = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        else -> "UNKNOWN"
    }
}
