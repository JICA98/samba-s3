package com.zenithblue.sambas3.monitoring

data class EmulatorMetrics(
    val timestampNs: Long = 0L,
    val presentedFrameCount: Long? = null,
    val vblankCount: Long? = null,
    val vblankDelta: Long? = null,
    val fpsSource: String? = null,
    val fps: Float? = null,
    val frameTimeMs: Float? = null,
    val hostCpuPercent: Float? = null,
    val ppuCpuPercent: Float? = null,
    val spuCpuPercent: Float? = null,
    val rsxCpuPercent: Float? = null,
    val ppuThreads: Int? = null,
    val spuThreads: Int? = null,
    val hostThreads: Int? = null,
    val rsxLoadPercent: Int? = null,
    val fpsSamples: List<Float> = emptyList(),
    val frameTimeSamples: List<Float> = emptyList(),
    val fpsTimedSamples: List<TimedSample> = emptyList(),
    val frameTimeTimedSamples: List<TimedSample> = emptyList()
)

data class MetricDebugInfo(val lastUpdatedAtMs: Long, val source: String)

data class GpuHardwareMetrics(val loadPercent: Int? = null, val frequencyHz: Long? = null)

data class AndroidSystemMetrics(
    val systemCpuPercent: Float? = null,
    val processCpuPercent: Float? = null,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val ramAvailableBytes: Long? = null,
    val processPssBytes: Long? = null,
    val processRssBytes: Long? = null,
    val swapUsedBytes: Long? = null,
    val swapTotalBytes: Long? = null,
    val zramUsedBytes: Long? = null,
    val batteryTemperatureC: Float? = null,
    val thermalStatus: Int? = null,
    val thermalHeadroom: Float? = null,
    val batteryPowerW: Float? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val cpuFrequenciesHz: List<Long> = emptyList(),
    val gpu: GpuHardwareMetrics? = null
)

data class MonitoringSnapshot(
    val emulator: EmulatorMetrics = EmulatorMetrics(),
    val android: AndroidSystemMetrics = AndroidSystemMetrics(),
    val fpsHistory: List<TimedSample> = emptyList(),
    val frameTimeHistory: List<TimedSample> = emptyList(),
    val metricDebug: Map<MonitoringMetric, MetricDebugInfo> = emptyMap(),
    val timestampNs: Long = System.nanoTime()
)
