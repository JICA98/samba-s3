package com.zenithblue.sambas3.monitoring

enum class MonitoringMetricCategory(val title: String) {
    FRAME("FRAME"), RPCSX("RPCSX"), ANDROID_GPU("ANDROID / GPU"), MEMORY("MEMORY"), POWER_THERMAL("POWER / THERMAL")
}

enum class MonitoringMetricSource { Emulator, Android, Gpu }

enum class MonitoringMetric {
    Fps, FrameTime,
    RpcsxHostCpu, PpuCpu, SpuCpu, RsxCpu, RsxLoad, PpuThreads, SpuThreads, HostThreads,
    AndroidSystemCpu, AndroidProcessCpu, GpuHardwareLoad, GpuFrequency, CpuFrequency,
    RamUsed, RamAvailable, RamTotal, AppRss, AppPss, SwapUsed, SwapTotal, ZramUsed,
    BatteryPercent, BatteryTemperature, BatteryPower, ThermalStatus, ThermalHeadroom
}

data class MonitoringMetricDescriptor(
    val metric: MonitoringMetric,
    val title: String,
    val shortLabel: String,
    val category: MonitoringMetricCategory,
    val source: MonitoringMetricSource,
    val unit: String? = null,
    val supportsGraph: Boolean = false
)

object MonitoringMetricDescriptors {
    val all: List<MonitoringMetricDescriptor> = listOf(
        d(MonitoringMetric.Fps, "FPS", "FPS", MonitoringMetricCategory.FRAME, MonitoringMetricSource.Emulator, "FPS", true),
        d(MonitoringMetric.FrameTime, "Frame time", "FRAME", MonitoringMetricCategory.FRAME, MonitoringMetricSource.Emulator, "ms", true),
        d(MonitoringMetric.RpcsxHostCpu, "Host CPU", "CPU", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "%"),
        d(MonitoringMetric.PpuCpu, "PPU CPU", "PPU", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "%"),
        d(MonitoringMetric.SpuCpu, "SPU CPU", "SPU", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "%"),
        d(MonitoringMetric.RsxCpu, "RSX CPU", "RSX CPU", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "%"),
        d(MonitoringMetric.RsxLoad, "RSX load", "RSX", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "%"),
        d(MonitoringMetric.PpuThreads, "PPU threads", "PPU THR", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "threads"),
        d(MonitoringMetric.SpuThreads, "SPU threads", "SPU THR", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "threads"),
        d(MonitoringMetric.HostThreads, "Host threads", "HOST THR", MonitoringMetricCategory.RPCSX, MonitoringMetricSource.Emulator, "threads"),
        d(MonitoringMetric.AndroidSystemCpu, "System CPU", "SYS CPU", MonitoringMetricCategory.ANDROID_GPU, MonitoringMetricSource.Android, "%"),
        d(MonitoringMetric.AndroidProcessCpu, "Process CPU", "APP CPU", MonitoringMetricCategory.ANDROID_GPU, MonitoringMetricSource.Android, "%"),
        d(MonitoringMetric.GpuHardwareLoad, "GPU hardware load", "GPU", MonitoringMetricCategory.ANDROID_GPU, MonitoringMetricSource.Gpu, "%"),
        d(MonitoringMetric.GpuFrequency, "GPU frequency", "GPU FREQ", MonitoringMetricCategory.ANDROID_GPU, MonitoringMetricSource.Gpu, "MHz"),
        d(MonitoringMetric.CpuFrequency, "CPU max frequency", "CPU MAX", MonitoringMetricCategory.ANDROID_GPU, MonitoringMetricSource.Android, "MHz"),
        d(MonitoringMetric.RamUsed, "RAM used", "RAM", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.RamAvailable, "RAM available", "AVAIL", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.RamTotal, "RAM total", "RAM TOTAL", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.AppRss, "App RSS", "RSS", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.AppPss, "App PSS", "PSS", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.SwapUsed, "Swap used", "SWAP", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.SwapTotal, "Swap total", "SWAP TOTAL", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.ZramUsed, "ZRAM used", "ZRAM", MonitoringMetricCategory.MEMORY, MonitoringMetricSource.Android, "bytes"),
        d(MonitoringMetric.BatteryPercent, "Battery", "BAT", MonitoringMetricCategory.POWER_THERMAL, MonitoringMetricSource.Android, "%"),
        d(MonitoringMetric.BatteryTemperature, "Battery temperature", "BAT TEMP", MonitoringMetricCategory.POWER_THERMAL, MonitoringMetricSource.Android, "°C"),
        d(MonitoringMetric.BatteryPower, "Battery power", "POWER", MonitoringMetricCategory.POWER_THERMAL, MonitoringMetricSource.Android, "W"),
        d(MonitoringMetric.ThermalStatus, "Thermal status", "THERMAL", MonitoringMetricCategory.POWER_THERMAL, MonitoringMetricSource.Android),
        d(MonitoringMetric.ThermalHeadroom, "Thermal headroom", "HEADROOM", MonitoringMetricCategory.POWER_THERMAL, MonitoringMetricSource.Android)
    )

    private fun d(metric: MonitoringMetric, title: String, shortLabel: String, category: MonitoringMetricCategory, source: MonitoringMetricSource, unit: String? = null, supportsGraph: Boolean = false) =
        MonitoringMetricDescriptor(metric, title, shortLabel, category, source, unit, supportsGraph)

    fun descriptor(metric: MonitoringMetric): MonitoringMetricDescriptor = all.first { it.metric == metric }
}

enum class MonitoringLayout { Compact, Grid, Detailed }
enum class FpsGraphScale { TargetWindow, ZeroBased, StableAuto }
