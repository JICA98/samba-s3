package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.monitoring.AndroidSystemMetrics
import com.zenithblue.sambas3.monitoring.EmulatorMetrics
import com.zenithblue.sambas3.monitoring.MonitoringPosition
import com.zenithblue.sambas3.monitoring.MonitoringPreset
import com.zenithblue.sambas3.monitoring.MonitoringRepository
import com.zenithblue.sambas3.monitoring.MonitoringSettings
import com.zenithblue.sambas3.monitoring.MonitoringGraphMath
import java.util.Locale

@Composable
fun MonitoringOverlay(repository: MonitoringRepository, settings: MonitoringSettings, menuOpen: Boolean = false) {
    if (!settings.enabled || (settings.hideWithMenu && menuOpen)) return
    val snapshot by repository.snapshot.collectAsStateWithLifecycle()
    val alignment = when (settings.position) {
        MonitoringPosition.TopLeft -> Alignment.TopStart; MonitoringPosition.TopCenter -> Alignment.TopCenter; MonitoringPosition.TopRight -> Alignment.TopEnd
        MonitoringPosition.BottomLeft -> Alignment.BottomStart; MonitoringPosition.BottomCenter -> Alignment.BottomCenter; MonitoringPosition.BottomRight -> Alignment.BottomEnd
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = alignment) {
        MetricPanel(snapshot.emulator, snapshot.android, settings, maxWidth.value)
    }
}

@Composable
private fun MetricPanel(emulator: EmulatorMetrics, android: AndroidSystemMetrics, settings: MonitoringSettings, width: Float) {
    val compact = settings.preset == MonitoringPreset.Minimal
    val developer = settings.preset == MonitoringPreset.Developer
    val bg = Color.Black.copy(alpha = settings.opacity.coerceIn(.2f, .92f))
    Column(Modifier.widthIn(max = if (width > 600f) 520.dp else 390.dp).background(bg).padding(horizontal = 10.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("FPS", emulator.fps?.let { "%.1f".formatUS(it) })
            Metric("FRAME", emulator.frameTimeMs?.let { "%.1f ms".formatUS(it) })
            Metric("TEMP", android.batteryTemperatureC?.let { "%.1f°C".formatUS(it) })
            Metric("POWER", android.batteryPowerW?.let { "%.1f W".formatUS(it) })
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("CPU", pct(emulator.hostCpuPercent ?: android.systemCpuPercent)); Metric("PPU", pct(emulator.ppuCpuPercent)); Metric("SPU", pct(emulator.spuCpuPercent)); Metric("RSX", pct(emulator.rsxLoadPercent?.toFloat()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("GPU", pct(android.gpu?.loadPercent?.toFloat())); Metric("RAM", bytes(android.ramUsedBytes)); Metric("APP", bytes(android.processRssBytes ?: android.processPssBytes)); Metric("SWAP", bytes(android.swapUsedBytes))
            }
        }
        if (developer) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("RSX CPU", pct(emulator.rsxCpuPercent)); Metric("THREADS", listOfNotNull(emulator.ppuThreads, emulator.spuThreads, emulator.hostThreads).joinToString("/" ).ifBlank { null }); Metric("GPU FREQ", android.gpu?.frequencyHz?.let { "${it / 1_000_000} MHz" })
            }
            Metric("THERMAL", android.thermalStatus?.let(::thermalLabel))
        }
        if (settings.showGraphs) {
            Graph("FPS", emulator.fpsSamples, Color(0xFF73E6B5), emulator.fps)
            Graph("FRAME (ms)", emulator.frameTimeSamples, Color(0xFFFFC857), emulator.frameTimeMs)
        }
    }
}

@Composable private fun Metric(label: String, value: String?) {
    if (value == null) return
    Column { Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall); Text(value, color = Color.White, style = MaterialTheme.typography.labelMedium) }
}

@Composable private fun Graph(label: String, values: List<Float>, color: Color, current: Float?) {
    val valid = MonitoringGraphMath.validSamples(values)
    if (valid.size < 2) return
    val scale = MonitoringGraphMath.autoScale(valid, fallbackUpper = if (label.startsWith("FRAME")) 33.33f else 60f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text("${current?.let { "%.1f".formatUS(it) } ?: "—"} / ${scale.upper.formatUS()}", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp).background(Color.White.copy(alpha = .06f))) {
            val path = Path()
            val normalized = MonitoringGraphMath.mapToUnit(valid, scale)
            listOf(.25f, .5f, .75f).forEach { fraction ->
                drawLine(Color.White.copy(alpha = .12f), androidx.compose.ui.geometry.Offset(0f, size.height * (1f - fraction)), androidx.compose.ui.geometry.Offset(size.width, size.height * (1f - fraction)))
            }
            normalized.forEachIndexed { index, value ->
                val x = index.toFloat() / normalized.lastIndex.coerceAtLeast(1) * size.width
                val y = size.height - value * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private fun pct(value: Float?): String? = value?.let { "%.0f%%".formatUS(it) }
private fun bytes(value: Long?): String? = value?.let { if (it >= 1_000_000_000) "%.1fG".formatUS(it / 1_000_000_000f) else "%.0fM".formatUS(it / 1_000_000f) }
private fun thermalLabel(status: Int): String = when (status) { 0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; else -> "UNKNOWN" }
private fun String.formatUS(value: Float): String = String.format(Locale.US, this, value)
private fun Float.formatUS(): String = String.format(Locale.US, "%.0f", this)
