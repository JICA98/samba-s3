package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.zenithblue.sambas3.monitoring.MonitoringGraphEntry
import com.zenithblue.sambas3.monitoring.MonitoringGraphMath
import com.zenithblue.sambas3.monitoring.MonitoringLayout
import com.zenithblue.sambas3.monitoring.MonitoringOverlayPresentation
import com.zenithblue.sambas3.monitoring.MonitoringPosition
import com.zenithblue.sambas3.monitoring.MonitoringRepository
import com.zenithblue.sambas3.monitoring.MonitoringSettings
import com.zenithblue.sambas3.monitoring.TimedSample

fun monitoringAlignment(position: MonitoringPosition): Alignment = when (position) {
    MonitoringPosition.TopLeft -> Alignment.TopStart
    MonitoringPosition.TopCenter -> Alignment.TopCenter
    MonitoringPosition.TopRight -> Alignment.TopEnd
    MonitoringPosition.BottomLeft -> Alignment.BottomStart
    MonitoringPosition.BottomCenter -> Alignment.BottomCenter
    MonitoringPosition.BottomRight -> Alignment.BottomEnd
}

@Composable
fun MonitoringOverlay(
    repository: MonitoringRepository,
    settings: MonitoringSettings,
    menuOpen: Boolean = false
) {
    if (!settings.enabled || (settings.hideWithMenu && menuOpen)) return
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val alignment = monitoringAlignment(settings.position)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = alignment
    ) {
        MetricPanel(
            e = snapshot.emulator,
            a = snapshot.android,
            fpsHistory = snapshot.fpsHistory,
            frameHistory = snapshot.frameTimeHistory,
            settings = settings
        )
    }
}

@Composable
fun MonitoringOverlayPreview(settings: MonitoringSettings) {
    val emulator = EmulatorMetrics(
        timestampNs = 1_000_000_000L,
        fps = 59.7f,
        frameTimeMs = 16.8f,
        hostCpuPercent = 24f,
        ppuCpuPercent = 14f,
        spuCpuPercent = 3f,
        rsxCpuPercent = 8f,
        ppuThreads = 13,
        spuThreads = 0,
        hostThreads = 96,
        rsxLoadPercent = 100,
        fpsSamples = listOf(59.7f, 59.8f),
        frameTimeSamples = listOf(16.7f, 16.8f)
    )
    val android = AndroidSystemMetrics(
        systemCpuPercent = 31f,
        processCpuPercent = 12f,
        ramUsedBytes = 4_200_000_000L,
        ramAvailableBytes = 3_800_000_000L,
        ramTotalBytes = 8_000_000_000L,
        processRssBytes = 600_000_000L,
        processPssBytes = 520_000_000L,
        batteryTemperatureC = 41f,
        batteryPowerW = 5.2f,
        batteryPercent = 72,
        charging = false,
        gpu = com.zenithblue.sambas3.monitoring.GpuHardwareMetrics(71, 650_000_000L),
        thermalStatus = 0
    )
    val history = listOf(TimedSample(900_000L, 59.7f), TimedSample(1_000_000L, 59.8f))
    val alignment = monitoringAlignment(settings.position)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp),
        contentAlignment = alignment
    ) {
        MetricPanel(
            e = emulator,
            a = android,
            fpsHistory = history,
            frameHistory = history.map { it.copy(value = 16.8f) },
            settings = settings
        )
    }
}

@Composable
private fun MetricPanel(
    e: EmulatorMetrics,
    a: AndroidSystemMetrics,
    fpsHistory: List<TimedSample>,
    frameHistory: List<TimedSample>,
    settings: MonitoringSettings
) {
    val bg = Color.Black.copy(alpha = settings.opacity.coerceIn(.05f, 1f))
    val panelWidth = when (settings.layout) {
        MonitoringLayout.Compact -> 220.dp
        MonitoringLayout.Grid -> 260.dp
        MonitoringLayout.Detailed -> 320.dp
    }
    val displayEntries = MonitoringOverlayPresentation.buildDisplayEntries(settings.enabledMetrics, e, a)
    val graphEntries = MonitoringOverlayPresentation.buildGraphEntries(settings.graphMetrics, e, fpsHistory, frameHistory)
    val columns = when (settings.layout) {
        MonitoringLayout.Compact -> 4
        MonitoringLayout.Grid, MonitoringLayout.Detailed -> 2
    }

    Column(
        modifier = Modifier
            .width(panelWidth)
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        displayEntries.chunked(columns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { entry ->
                    Metric(
                        label = entry.label,
                        value = entry.displayValue,
                        scale = settings.textScale,
                        detailed = settings.layout == MonitoringLayout.Detailed,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        graphEntries.forEach { graph ->
            GraphView(graph = graph, settings = settings)
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    scale: Float,
    detailed: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = label,
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.labelSmall.scaled(scale),
            maxLines = 1
        )
        Text(
            text = value,
            color = Color.White,
            style = (if (detailed) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium).scaled(scale),
            maxLines = 1
        )
    }
}

@Composable
private fun GraphView(
    graph: MonitoringGraphEntry,
    settings: MonitoringSettings
) {
    val color = if (graph.isFrameTime) Color(0xFFFFC857) else Color(0xFF73E6B5)
    Column(Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = graph.label,
                color = RPCSXColors.textSecondary,
                style = MaterialTheme.typography.labelSmall.scaled(settings.textScale)
            )
            Text(
                text = graph.currentValue ?: "—",
                color = RPCSXColors.textSecondary,
                style = MaterialTheme.typography.labelSmall.scaled(settings.textScale)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(Color.White.copy(alpha = .06f)),
            contentAlignment = Alignment.Center
        ) {
            if (graph.isPlaceholder) {
                Text(
                    text = "COLLECTING...",
                    color = Color.White.copy(alpha = 0.35f),
                    style = MaterialTheme.typography.labelSmall.scaled(settings.textScale)
                )
            } else {
                val samples = graph.samples
                val nowUs = samples.last().timestampUs
                val values = samples.map { it.value }
                val targetFps = graph.samples.lastOrNull()?.value?.let { if (it < 45f) 30f else 60f } ?: 60f
                val scale = if (graph.isFrameTime) {
                    MonitoringGraphMath.frameTimeScale(values, targetFps)
                } else {
                    MonitoringGraphMath.fpsScale(values, settings.fpsScaleMode, targetFps)
                }
                Canvas(Modifier.fillMaxSize()) {
                    val path = Path()
                    samples.forEachIndexed { index, sample ->
                        val x = MonitoringGraphMath.timestampX(sample.timestampUs, nowUs, settings.graphHistorySeconds) * size.width
                        val normalized = ((sample.value - scale.lower) / (scale.upper - scale.lower).coerceAtLeast(.001f)).coerceIn(0f, 1f)
                        val y = size.height - normalized * size.height
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawLine(
                        Color.White.copy(alpha = .14f),
                        androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        androidx.compose.ui.geometry.Offset(size.width, size.height / 2)
                    )
                    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }
    }
}

private fun TextStyle.scaled(scale: Float) = copy(fontSize = (fontSize.value * scale.coerceIn(.75f, 1.25f)).sp)
