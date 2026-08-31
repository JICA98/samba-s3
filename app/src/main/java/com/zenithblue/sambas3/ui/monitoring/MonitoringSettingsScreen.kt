package com.zenithblue.sambas3.ui.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.monitoring.MonitoringOverlaySettings
import com.zenithblue.sambas3.monitoring.MonitoringPreset
import com.zenithblue.sambas3.monitoring.MonitoringSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringSettingsScreen(navigateBack: () -> Unit, isInSplitPane: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(MonitoringOverlaySettings.read(context)) }
    fun save(next: MonitoringSettings) { settings = next; MonitoringOverlaySettings.write(context, next) }
    val content = @Composable {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("PERFORMANCE MONITOR", color = RPCSXColors.primary, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Text("Compose presentation of RPCSX metrics and best-effort Android telemetry. Unexposed device values stay hidden.", color = RPCSXColors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Enabled", color = RPCSXColors.textPrimary); Switch(settings.enabled, { save(settings.copy(enabled = it)) }) }
            HorizontalDivider()
            Text("Preset", color = RPCSXColors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { MonitoringPreset.entries.forEach { preset -> FilterChip(selected = settings.preset == preset, onClick = { save(settings.copy(preset = preset)) }, label = { Text(preset.name) }) } }
            Text("Update interval: ${settings.updateMs} ms", color = RPCSXColors.textSecondary)
            Slider(value = settings.updateMs.toFloat(), onValueChange = { save(settings.copy(updateMs = it.toLong())) }, valueRange = 250f..1000f, steps = 6)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Graphs", color = RPCSXColors.textPrimary); Switch(settings.showGraphs, { save(settings.copy(showGraphs = it)) }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Hide while menu is open", color = RPCSXColors.textPrimary); Switch(settings.hideWithMenu, { save(settings.copy(hideWithMenu = it)) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = navigateBack) { Text("DONE") }; OutlinedButton(onClick = { save(MonitoringSettings()) }) { Text("RESET") } }
        }
    }
    if (isInSplitPane) {
        Surface(color = RPCSXColors.surfaceElevated, tonalElevation = 6.dp, modifier = Modifier.fillMaxSize().padding(18.dp)) { content() }
    } else Scaffold(topBar = { LargeTopAppBar(title = { Text("Performance Monitor") }, navigationIcon = { androidx.compose.material3.TextButton(onClick = navigateBack) { Text("BACK") } }) }) { padding -> Column(Modifier.padding(padding)) { content() } }
}
