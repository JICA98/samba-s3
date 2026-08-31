package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.monitoring.FpsGraphScale
import com.zenithblue.sambas3.monitoring.MonitoringLayout
import com.zenithblue.sambas3.monitoring.MonitoringMetric
import com.zenithblue.sambas3.monitoring.MonitoringMetricCategory
import com.zenithblue.sambas3.monitoring.MonitoringMetricDescriptors
import com.zenithblue.sambas3.monitoring.MonitoringOverlaySettings
import com.zenithblue.sambas3.monitoring.MonitoringPosition
import com.zenithblue.sambas3.monitoring.MonitoringPreset
import com.zenithblue.sambas3.monitoring.MonitoringPresets
import com.zenithblue.sambas3.monitoring.MonitoringSettings

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun MonitoringSettingsScreen(navigateBack: () -> Unit, isInSplitPane: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(MonitoringOverlaySettings.read(context)) }
    var intervalDraft by remember(settings.updateMs) { mutableFloatStateOf(settings.updateMs.toFloat()) }
    var opacityDraft by remember(settings.opacity) { mutableFloatStateOf(settings.opacity) }
    var textScaleDraft by remember(settings.textScale) { mutableFloatStateOf(settings.textScale) }
    fun save(next: MonitoringSettings) { settings = next; MonitoringOverlaySettings.write(context, next) }
    fun setMetric(metric: MonitoringMetric, enabled: Boolean) {
        val next = settings.enabledMetrics.toMutableSet().apply { if (enabled) add(metric) else remove(metric) }
        val graphs = settings.graphMetrics.filterTo(mutableSetOf()) { it in next }
        save(settings.copy(enabledMetrics = next, graphMetrics = graphs))
    }
    fun setGraph(metric: MonitoringMetric, enabled: Boolean) {
        val graphs = settings.graphMetrics.toMutableSet().apply { if (enabled) add(metric) else remove(metric) }
        val metrics = settings.enabledMetrics.toMutableSet().apply { if (enabled) add(metric) }
        save(settings.copy(enabledMetrics = metrics, graphMetrics = graphs))
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PERFORMANCE MONITOR", color = RPCSXColors.primary, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text("Choose each metric independently. Presets are templates; editing one metric creates Custom.", color = RPCSXColors.textSecondary)
            Text("PREVIEW", color = RPCSXColors.primary, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
            Surface(color = RPCSXColors.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) { MonitoringOverlayPreview(settings) }
            SettingSwitch("Enabled", settings.enabled) { save(settings.copy(enabled = it)) }
            HorizontalDivider()
            SectionLabel("GENERAL")
            Text("Preset: ${settings.preset.name}", color = RPCSXColors.textSecondary)
            ChipRow(MonitoringPreset.entries.filter { it != MonitoringPreset.Custom }, settings.preset) { preset ->
                save(settings.copy(enabledMetrics = MonitoringPresets.forPreset(preset), graphMetrics = settings.graphMetrics.filterTo(mutableSetOf()) { it in MonitoringPresets.forPreset(preset) }))
            }
            Text("Position", color = RPCSXColors.textPrimary)
            ChipRow(MonitoringPosition.entries.toList(), settings.position) { save(settings.copy(position = it)) }
            Text("Layout", color = RPCSXColors.textPrimary)
            ChipRow(MonitoringLayout.entries.toList(), settings.layout) { save(settings.copy(layout = it)) }
            Text("Update interval: ${intervalDraft.toLong()} ms", color = RPCSXColors.textSecondary)
            Slider(value = intervalDraft, onValueChange = { intervalDraft = it }, onValueChangeFinished = { save(settings.copy(updateMs = intervalDraft.toLong())) }, valueRange = 250f..1000f, steps = 6)
            Text("Opacity: ${(opacityDraft * 100).toInt()}%", color = RPCSXColors.textSecondary)
            Slider(value = opacityDraft, onValueChange = { opacityDraft = it }, onValueChangeFinished = { save(settings.copy(opacity = opacityDraft)) }, valueRange = .05f..1f, steps = 18)
            Text("Text size: ${(textScaleDraft * 100).toInt()}%", color = RPCSXColors.textSecondary)
            Slider(value = textScaleDraft, onValueChange = { textScaleDraft = it }, onValueChangeFinished = { save(settings.copy(textScale = textScaleDraft)) }, valueRange = .75f..1.25f, steps = 9)
            SettingSwitch("Hide while menu is open", settings.hideWithMenu) { save(settings.copy(hideWithMenu = it)) }
            HorizontalDivider()
            SectionLabel("GRAPHS")
            Text("History: ${settings.graphHistorySeconds}s", color = RPCSXColors.textSecondary)
            ChipRow(listOf(5, 10, 20, 30), settings.graphHistorySeconds) { save(settings.copy(graphHistorySeconds = it)) }
            Text("Graphs always fill the monitor body width.", color = RPCSXColors.textSecondary)
            Text("FPS scale", color = RPCSXColors.textPrimary)
            ChipRow(FpsGraphScale.entries.toList(), settings.fpsScaleMode) { save(settings.copy(fpsScaleMode = it)) }
            MonitoringMetricCategory.entries.forEach { category ->
                HorizontalDivider()
                SectionLabel(category.title)
                MonitoringMetricDescriptors.all.filter { it.category == category }.forEach { descriptor ->
                    val checked = descriptor.metric in settings.enabledMetrics
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(descriptor.title, color = RPCSXColors.textPrimary)
                                Text(availabilityText(descriptor.metric), color = RPCSXColors.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = checked, onCheckedChange = { setMetric(descriptor.metric, it) })
                        }
                        if (descriptor.supportsGraph) {
                            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Graph", color = RPCSXColors.textSecondary, modifier = Modifier.weight(1f))
                                Switch(checked = descriptor.metric in settings.graphMetrics, onCheckedChange = { setGraph(descriptor.metric, it) })
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = navigateBack) { Text("DONE") }
                OutlinedButton(onClick = { save(MonitoringSettings()); intervalDraft = 300f; opacityDraft = .72f; textScaleDraft = .88f }) { Text("RESET") }
            }
        }
    }
    if (isInSplitPane) Surface(color = RPCSXColors.surfaceElevated, tonalElevation = 6.dp, modifier = Modifier.fillMaxSize().padding(12.dp)) { content() }
    else Scaffold(topBar = { LargeTopAppBar(title = { Text("Performance Monitor") }, navigationIcon = { TextButton(onClick = navigateBack) { Text("BACK") } }) }) { padding -> Column(Modifier.padding(padding)) { content() } }
}

@Composable private fun SectionLabel(text: String) { Text(text, color = RPCSXColors.primary, style = androidx.compose.material3.MaterialTheme.typography.labelLarge) }

@Composable private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = RPCSXColors.textPrimary); Switch(checked, onCheckedChange) }
}

@Composable private fun <T> ChipRow(values: List<T>, selected: T, onClick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) { values.forEach { value -> FilterChip(selected = value == selected, onClick = { onClick(value) }, label = { Text(value.toString()) }) } }
}

private fun availabilityText(metric: MonitoringMetric): String = when (metric) {
    MonitoringMetric.Fps -> "Actual presented emu_flip frames per second; unavailable while paused."
    MonitoringMetric.FrameTime -> "Average interval between presented emu_flip frames; the graph retains per-frame spikes."
    MonitoringMetric.RpcsxHostCpu -> "RPCSX host CPU usage; 100% means one fully used host core."
    MonitoringMetric.PpuCpu -> "PPU share of RPCSX host CPU; may be steady in a stable scene."
    MonitoringMetric.SpuCpu -> "SPU share of RPCSX host CPU; may be steady in a stable scene."
    MonitoringMetric.RsxCpu -> "RSX host CPU share, separate from RSX guest load."
    MonitoringMetric.RsxLoad -> "RPCSX RSX workload estimate; can remain high during rendering."
    MonitoringMetric.PpuThreads -> "Current PPU thread count; structural and usually changes infrequently."
    MonitoringMetric.SpuThreads -> "Current SPU thread count; structural and usually changes infrequently."
    MonitoringMetric.HostThreads -> "Current native process thread count; usually changes infrequently."
    MonitoringMetric.GpuHardwareLoad, MonitoringMetric.GpuFrequency -> "Best effort; unavailable if the device exposes no GPU counter"
    MonitoringMetric.AndroidProcessCpu -> "Process CPU usage; 100% equals one fully used CPU core."
    MonitoringMetric.CpuFrequency -> "Maximum current frequency across online CPU cores."
    MonitoringMetric.RamAvailable -> "Available system memory, not strict Linux free pages."
    MonitoringMetric.RamTotal -> "Physical system memory; normally does not change during a session."
    MonitoringMetric.RamUsed -> "System memory used, derived from total minus available."
    MonitoringMetric.AppRss -> "Process resident set size from /proc; sampled independently from PSS."
    MonitoringMetric.AppPss -> "Android proportional set size; sampled less often than RSS."
    MonitoringMetric.SwapTotal -> "Swap capacity; structural and normally static."
    MonitoringMetric.BatteryPercent -> "Battery charge level; expected to update slowly."
    MonitoringMetric.BatteryTemperature -> "Battery sensor temperature, not necessarily SoC temperature."
    MonitoringMetric.ThermalStatus -> "Android system thermal severity; may use SoC/GPU/skin sensors."
    MonitoringMetric.ThermalHeadroom -> "Android thermal headroom; hidden when the API is unsupported."
    MonitoringMetric.BatteryPower -> "Voltage × battery current; hidden when vendor units are invalid."
    MonitoringMetric.SwapUsed -> "Current swap used from /proc/meminfo."
    MonitoringMetric.ZramUsed -> "Compressed zram usage from the kernel memory stats."
    MonitoringMetric.AndroidSystemCpu -> "System CPU usage from /proc/stat, normalized to 0–100%."
    else -> "Android system metric"
}
