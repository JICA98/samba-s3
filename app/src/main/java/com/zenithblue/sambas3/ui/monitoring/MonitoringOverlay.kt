package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.monitoring.AndroidSystemMetrics
import com.zenithblue.sambas3.monitoring.EmulatorMetrics
import com.zenithblue.sambas3.monitoring.MonitoringPosition
import com.zenithblue.sambas3.monitoring.MonitoringPreset
import com.zenithblue.sambas3.monitoring.MonitoringRepository
import com.zenithblue.sambas3.monitoring.MonitoringSettings
import java.util.Locale

@Composable
fun MonitoringOverlay(repository: MonitoringRepository, settings: MonitoringSettings, menuOpen: Boolean = false) {
    if (!settings.enabled || (settings.hideWithMenu && menuOpen)) return
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val alignment = when (settings.position) {
        MonitoringPosition.TopLeft -> Alignment.TopStart
        MonitoringPosition.TopCenter -> Alignment.TopCenter
        MonitoringPosition.TopRight -> Alignment.TopEnd
        MonitoringPosition.BottomLeft -> Alignment.BottomStart
        MonitoringPosition.BottomCenter -> Alignment.BottomCenter
        MonitoringPosition.BottomRight -> Alignment.BottomEnd
    }
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = alignment) {
        MetricPanel(snapshot.emulator, snapshot.android, settings)
    }
}

@Composable
private fun MetricPanel(emulator: EmulatorMetrics, android: AndroidSystemMetrics, settings: MonitoringSettings) {
    val compact = settings.preset == MonitoringPreset.Minimal
    val bg = Color.Black.copy(alpha = (1f - settings.opacity).coerceIn(.08f, .45f))
    Column(Modifier.widthIn(max = 360.dp).background(bg).padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("FPS", emulator.fps?.let { String.format(Locale.US, "%.1f", it) } ?: "—")
            Metric("FRAME", emulator.frameTimeMs?.let { String.format(Locale.US, "%.1fms", it) } ?: "—")
            Metric("TEMP", android.batteryTemperatureC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—")
        }
        if (!compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("RPCSX CPU", pct(emulator.hostCpuPercent))
                Metric("PPU", pct(emulator.ppuCpuPercent))
                Metric("SPU", pct(emulator.spuCpuPercent))
                Metric("RSX", pct(emulator.rsxCpuPercent))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("RSX LOAD", emulator.rsxLoadPercent?.let { "$it%" } ?: "—")
                Metric("ANDROID", pct(android.systemCpuPercent))
                Metric("APP RAM", bytes(android.processPssBytes))
                Metric("RAM", bytes(android.ramUsedBytes))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("GPU HW", android.gpu?.loadPercent?.let { "$it%" } ?: "—")
                Metric("POWER", android.batteryPowerW?.let { String.format(Locale.US, "%.1fW", it) } ?: "—")
                Metric("SWAP", bytes(android.swapUsedBytes))
            }
        }
        if (settings.showGraphs && emulator.fpsSamples.size > 1) {
            Graph(emulator.fpsSamples, Modifier.widthIn(min = 180.dp, max = 340.dp))
        }
    }
}

@Composable private fun Metric(label: String, value: String) {
    Column { Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall); Text(value, color = Color.White, style = MaterialTheme.typography.labelMedium) }
}

@Composable private fun Graph(values: List<Float>, modifier: Modifier) {
    Canvas(modifier.padding(top = 4.dp).background(Color.White.copy(alpha = .06f))) {
        val min = values.minOrNull() ?: 0f; val max = values.maxOrNull() ?: 1f; val range = (max - min).coerceAtLeast(.001f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index.toFloat() / (values.lastIndex.coerceAtLeast(1)) * size.width
            val y = size.height - ((value - min) / range * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color(0xFF73E6B5), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

private fun pct(value: Float?): String = value?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—"
private fun bytes(value: Long?): String = value?.let { if (it >= 1_000_000_000) String.format(Locale.US, "%.1fG", it / 1_000_000_000f) else String.format(Locale.US, "%.0fM", it / 1_000_000f) } ?: "—"
