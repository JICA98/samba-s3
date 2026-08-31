package com.zenithblue.sambas3.monitoring

object MonitoringPresets {
    val minimal: Set<MonitoringMetric> = linkedSetOf(MonitoringMetric.Fps, MonitoringMetric.FrameTime)
    val performance: Set<MonitoringMetric> = linkedSetOf(
        MonitoringMetric.Fps, MonitoringMetric.FrameTime, MonitoringMetric.RpcsxHostCpu,
        MonitoringMetric.PpuCpu, MonitoringMetric.RsxLoad, MonitoringMetric.AndroidProcessCpu,
        MonitoringMetric.CpuFrequency, MonitoringMetric.RamUsed, MonitoringMetric.BatteryTemperature
    )
    val developer: Set<MonitoringMetric> = MonitoringMetric.entries.toSet()

    fun forPreset(preset: MonitoringPreset): Set<MonitoringMetric> = when (preset) {
        MonitoringPreset.Minimal -> minimal
        MonitoringPreset.Performance -> performance
        MonitoringPreset.Developer -> developer
        MonitoringPreset.Custom -> emptySet()
    }

    fun presetFor(metrics: Set<MonitoringMetric>): MonitoringPreset = when (metrics) {
        minimal -> MonitoringPreset.Minimal
        performance -> MonitoringPreset.Performance
        developer -> MonitoringPreset.Developer
        else -> MonitoringPreset.Custom
    }
}
