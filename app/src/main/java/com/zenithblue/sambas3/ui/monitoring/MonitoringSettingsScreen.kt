package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import com.zenithblue.sambas3.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.common.SambaScreenScaffold
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Choose each metric independently. Presets are templates.",
                color = RPCSXColors.textSecondary,
                fontSize = 12.sp
            )

            // 21:9 Widescreen Two-Column Split Layout
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left Column: Metrics & Categories (Scrollable)
                Column(
                    Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x28FFFFFF),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingSwitch("Enabled", settings.enabled) { save(settings.copy(enabled = it)) }
                            SettingSwitch("Hide while menu is open", settings.hideWithMenu) { save(settings.copy(hideWithMenu = it)) }
                        }
                    }

                    MonitoringMetricCategory.entries.forEach { category ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0x28FFFFFF),
                            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionLabel(category.title)
                                MonitoringMetricDescriptors.all.filter { it.category == category }.forEach { descriptor ->
                                    val checked = descriptor.metric in settings.enabledMetrics
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(descriptor.title, color = RPCSXColors.textPrimary)
                                                Text(
                                                    availabilityText(descriptor.metric),
                                                    color = RPCSXColors.textSecondary,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
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
                        }
                    }
                }

                // Right Column: Preview & Controls (Scrollable)
                Column(
                    Modifier
                        .weight(0.9f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SectionLabel("PREVIEW")
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xEB0A0D14),
                        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 220.dp)
                    ) {
                        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.TopStart) {
                            MonitoringOverlayPreview(settings)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x28FFFFFF),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("PRESET")
                            ChipRow(MonitoringPreset.entries.filter { it != MonitoringPreset.Custom }, settings.preset) { preset ->
                                save(settings.copy(enabledMetrics = MonitoringPresets.forPreset(preset), graphMetrics = settings.graphMetrics.filterTo(mutableSetOf()) { it in MonitoringPresets.forPreset(preset) }))
                            }

                            SectionLabel("POSITION")
                            ChipRow(MonitoringPosition.entries.toList(), settings.position) { save(settings.copy(position = it)) }

                            SectionLabel("LAYOUT")
                            ChipRow(MonitoringLayout.entries.toList(), settings.layout) { save(settings.copy(layout = it)) }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x28FFFFFF),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionLabel("APPEARANCE")
                            Text("Update interval: ${intervalDraft.toLong()} ms", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Slider(value = intervalDraft, onValueChange = { intervalDraft = it }, onValueChangeFinished = { save(settings.copy(updateMs = intervalDraft.toLong())) }, valueRange = 250f..1000f, steps = 6)

                            Text("Opacity: ${(opacityDraft * 100).toInt()}%", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Slider(value = opacityDraft, onValueChange = { opacityDraft = it }, onValueChangeFinished = { save(settings.copy(opacity = opacityDraft)) }, valueRange = .05f..1f, steps = 18)

                            Text("Text size: ${(textScaleDraft * 100).toInt()}%", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                            Slider(value = textScaleDraft, onValueChange = { textScaleDraft = it }, onValueChangeFinished = { save(settings.copy(textScale = textScaleDraft)) }, valueRange = .50f..1.25f, steps = 14)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x28FFFFFF),
                        border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("GRAPHS")
                            Text("History: ${settings.graphHistorySeconds}s", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                            ChipRow(listOf(5, 10, 20, 30), settings.graphHistorySeconds) { save(settings.copy(graphHistorySeconds = it)) }

                            Text("FPS scale", color = RPCSXColors.textPrimary, style = MaterialTheme.typography.labelSmall)
                            ChipRow(FpsGraphScale.entries.toList(), settings.fpsScaleMode) { save(settings.copy(fpsScaleMode = it)) }
                        }
                    }
                }
            }
        }
    }

    if (isInSplitPane) {
        SambaScreenScaffold(
            title = "PERFORMANCE MONITOR",
            iconRes = R.drawable.ic_video,
            onBack = navigateBack,
            compact = true,
            showHints = false,
            actions = {
                MonitoringResetAction(
                    onReset = {
                        save(MonitoringSettings())
                        intervalDraft = 300f
                        opacityDraft = .72f
                        textScaleDraft = .70f
                    }
                )
            },
        ) { content() }
    } else {
        SambaScreenScaffold(
            title = "PERFORMANCE MONITOR",
            iconRes = R.drawable.ic_video,
            onBack = navigateBack,
            hints = listOf(
                R.drawable.cross to "Toggle",
                R.drawable.circle to "Back"
            ),
            actions = {
                MonitoringResetAction(
                    onReset = {
                        save(MonitoringSettings())
                        intervalDraft = 300f
                        opacityDraft = .72f
                        textScaleDraft = .70f
                    }
                )
                Surface(
                    onClick = navigateBack,
                    shape = RoundedCornerShape(8.dp),
                    color = RPCSXColors.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("DONE", color = RPCSXColors.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            },
        ) { content() }
    }
}

@Composable
private fun MonitoringResetAction(onReset: () -> Unit) {
    OutlinedButton(
        onClick = onReset,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text("RESET", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
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
}
