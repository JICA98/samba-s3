package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.monitoring.AndroidSystemMetrics
import com.zenithblue.sambas3.monitoring.EmulatorMetrics
import com.zenithblue.sambas3.monitoring.MonitoringGraphMath
import com.zenithblue.sambas3.monitoring.MonitoringMetric
import com.zenithblue.sambas3.monitoring.MonitoringMetricDescriptors
import com.zenithblue.sambas3.monitoring.MonitoringLayout
import com.zenithblue.sambas3.monitoring.MonitoringPosition
import com.zenithblue.sambas3.monitoring.MonitoringRepository
import com.zenithblue.sambas3.monitoring.MonitoringSettings
import com.zenithblue.sambas3.monitoring.TimedSample
import java.util.Locale

@Composable
fun MonitoringOverlay(repository: MonitoringRepository, settings: MonitoringSettings, menuOpen: Boolean = false) {
    if (!settings.enabled || (settings.hideWithMenu && menuOpen)) return
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val alignment = when (settings.position) {
        MonitoringPosition.TopLeft -> Alignment.TopStart; MonitoringPosition.TopCenter -> Alignment.TopCenter; MonitoringPosition.TopRight -> Alignment.TopEnd
        MonitoringPosition.BottomLeft -> Alignment.BottomStart; MonitoringPosition.BottomCenter -> Alignment.BottomCenter; MonitoringPosition.BottomRight -> Alignment.BottomEnd
    }
    BoxWithConstraints(Modifier.padding(6.dp), contentAlignment = alignment) {
        MetricPanel(snapshot.emulator, snapshot.android, snapshot.fpsHistory, snapshot.frameTimeHistory, settings)
    }
}

@Composable
fun MonitoringOverlayPreview(settings: MonitoringSettings) {
    val emulator = EmulatorMetrics(
        timestampNs = 1_000_000_000L, fps = 59.7f, frameTimeMs = 16.8f,
        hostCpuPercent = 24f, ppuCpuPercent = 14f, spuCpuPercent = 3f, rsxCpuPercent = 8f,
        ppuThreads = 13, spuThreads = 0, hostThreads = 96, rsxLoadPercent = 100,
        fpsSamples = listOf(59.7f, 59.8f), frameTimeSamples = listOf(16.7f, 16.8f)
    )
    val android = AndroidSystemMetrics(
        systemCpuPercent = 31f, processCpuPercent = 12f, ramUsedBytes = 4_200_000_000L,
        ramAvailableBytes = 3_800_000_000L, ramTotalBytes = 8_000_000_000L,
        processRssBytes = 600_000_000L, processPssBytes = 520_000_000L,
        batteryTemperatureC = 41f, batteryPowerW = 5.2f, batteryPercent = 72, charging = false,
        gpu = com.zenithblue.sambas3.monitoring.GpuHardwareMetrics(71, 650_000_000L),
        thermalStatus = 0
    )
    val history = listOf(TimedSample(900_000L, 59.7f), TimedSample(1_000_000L, 59.8f))
    MetricPanel(emulator, android, history, history.map { it.copy(value = 16.8f) }, settings)
}

@Composable
private fun MetricPanel(e: EmulatorMetrics, a: AndroidSystemMetrics, fpsHistory: List<TimedSample>, frameHistory: List<TimedSample>, settings: MonitoringSettings) {
    val bg = Color.Black.copy(alpha = settings.opacity.coerceIn(.05f, 1f))
    val descriptors = settings.enabledMetrics.mapNotNull { metric -> MonitoringMetricDescriptors.descriptor(metric).takeIf { metricValue(metric, e, a) != null } }
    val panelWidth = when (settings.layout) {
        MonitoringLayout.Compact -> 210.dp
        MonitoringLayout.Grid -> 250.dp
        MonitoringLayout.Detailed -> 300.dp
    }
    val displayMetrics = displayMetrics(descriptors.map { it.metric }, e, a)
    Column(Modifier.width(panelWidth).background(bg).padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val columns = when (settings.layout) { MonitoringLayout.Compact -> 4; MonitoringLayout.Grid, MonitoringLayout.Detailed -> 2 }
        displayMetrics.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Metric(label, value, settings.textScale, settings.layout == MonitoringLayout.Detailed, Modifier.weight(1f))
                }
            }
        }
        if (MonitoringMetric.Fps in settings.graphMetrics) Graph("FPS", fpsHistory, Color(0xFF73E6B5), e.fps, settings, false)
        if (MonitoringMetric.FrameTime in settings.graphMetrics) Graph("FRAME", frameHistory, Color(0xFFFFC857), e.frameTimeMs, settings, true)
    }
}

@Composable
private fun Metric(label: String, value: String?, scale: Float, detailed: Boolean, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(modifier.widthIn(min = if (detailed) 62.dp else 48.dp)) {
        Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall.scaled(scale), maxLines = 1)
        Text(value, color = Color.White, style = (if (detailed) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium).scaled(scale), maxLines = 1)
    }
}

@Composable
private fun Graph(label: String, samples: List<TimedSample>, color: Color, current: Float?, settings: MonitoringSettings, frameTime: Boolean) {
    if (samples.size < 2) return
    val nowUs = samples.last().timestampUs
    val values = samples.map { it.value }
    val targetFps = current?.let { if (it < 45f) 30f else 60f } ?: 60f
    val scale = if (frameTime) MonitoringGraphMath.frameTimeScale(values, targetFps) else MonitoringGraphMath.fpsScale(values, settings.fpsScaleMode, targetFps)
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall.scaled(settings.textScale))
            Text(current?.let { if (frameTime) "%.1f ms".formatUS(it) else "%.1f".formatUS(it) } ?: "—", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall.scaled(settings.textScale))
        }
        Canvas(Modifier.fillMaxWidth().height(38.dp).background(Color.White.copy(alpha = .06f))) {
            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = MonitoringGraphMath.timestampX(sample.timestampUs, nowUs, settings.graphHistorySeconds) * size.width
                val normalized = ((sample.value - scale.lower) / (scale.upper - scale.lower).coerceAtLeast(.001f)).coerceIn(0f, 1f)
                val y = size.height - normalized * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(Color.White.copy(alpha = .14f), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2))
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

private fun displayMetrics(metrics: List<MonitoringMetric>, e: EmulatorMetrics, a: AndroidSystemMetrics): List<Pair<String, String>> = buildList {
    val available = metrics.toSet()
    val threadValues = listOf(e.ppuThreads, e.spuThreads, e.hostThreads)
    var threadsAdded = false
    var ramAdded = false
    var swapAdded = false
    metrics.forEach { metric ->
        when (metric) {
            MonitoringMetric.PpuThreads, MonitoringMetric.SpuThreads, MonitoringMetric.HostThreads -> {
                if (!threadsAdded && available.containsAll(setOf(MonitoringMetric.PpuThreads, MonitoringMetric.SpuThreads, MonitoringMetric.HostThreads)) && threadValues.all { it != null }) {
                    add("THR" to "${e.ppuThreads} / ${e.spuThreads} / ${e.hostThreads}")
                    threadsAdded = true
                } else if (!threadsAdded && metricValue(metric, e, a) != null) add(MonitoringMetricDescriptors.descriptor(metric).shortLabel to metricValue(metric, e, a)!!)
            }
            MonitoringMetric.RamUsed -> {
                if (!ramAdded && MonitoringMetric.RamTotal in available && a.ramUsedBytes != null && a.ramTotalBytes != null) {
                    add("RAM" to "${bytes(a.ramUsedBytes)} / ${bytes(a.ramTotalBytes)}")
                    ramAdded = true
                } else if (!ramAdded) metricValue(metric, e, a)?.let { add("RAM" to it) }
            }
            MonitoringMetric.RamTotal -> if (!ramAdded) metricValue(metric, e, a)?.let { add("RAM TOTAL" to it) }
            MonitoringMetric.SwapUsed -> {
                if (!swapAdded && MonitoringMetric.SwapTotal in available && a.swapUsedBytes != null && a.swapTotalBytes != null) {
                    add("SWAP" to "${bytes(a.swapUsedBytes)} / ${bytes(a.swapTotalBytes)}")
                    swapAdded = true
                } else if (!swapAdded) metricValue(metric, e, a)?.let { add("SWAP" to it) }
            }
            MonitoringMetric.SwapTotal -> if (!swapAdded) metricValue(metric, e, a)?.let { add("SWAP TOTAL" to it) }
            else -> metricValue(metric, e, a)?.let { add(MonitoringMetricDescriptors.descriptor(metric).shortLabel to it) }
        }
    }
}

private fun metricValue(metric: MonitoringMetric, e: EmulatorMetrics, a: AndroidSystemMetrics): String? = when (metric) {
    MonitoringMetric.Fps -> e.fps?.let { "%.1f".formatUS(it) }
    MonitoringMetric.FrameTime -> e.frameTimeMs?.let { "%.1f ms".formatUS(it) }
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
    MonitoringMetric.BatteryTemperature -> a.batteryTemperatureC?.let { "%.1f°C".formatUS(it) }
    MonitoringMetric.BatteryPower -> a.batteryPowerW?.let { "%.1fW ${if (a.charging == true) "↑" else "↓"}".formatUS(it) }
    MonitoringMetric.ThermalStatus -> a.thermalStatus?.let(::thermalLabel)
    MonitoringMetric.ThermalHeadroom -> a.thermalHeadroom?.let { "%.1f".formatUS(it) }
}

private fun pct(value: Float?): String? = value?.let { "%.0f%%".formatUS(it) }
private fun bytes(value: Long?): String? = value?.let { if (it >= 1_000_000_000) "%.1fG".formatUS(it / 1_000_000_000f) else "%.0fM".formatUS(it / 1_000_000f) }
private fun thermalLabel(status: Int): String = when (status) { 0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; else -> "UNKNOWN" }
private fun TextStyle.scaled(scale: Float) = copy(fontSize = (fontSize.value * scale.coerceIn(.75f, 1.25f)).sp)
private fun String.formatUS(value: Float): String = String.format(Locale.US, this, value)
private fun Float.formatUS(): String = String.format(Locale.US, if (this % 1f == 0f) "%.0f" else "%.1f", this)
