package com.zenithblue.sambas3.ui.logging

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.LogLevel
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.logging.LiveLogOverlaySettings
import com.zenithblue.sambas3.logging.LiveLogOverlaySettingsData
import com.zenithblue.sambas3.logging.LogBroker
import com.zenithblue.sambas3.logging.LogSourceKind
import com.zenithblue.sambas3.ui.ingame.InGameMenuIntent

@Composable
fun InGameLiveLogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        LogBroker.retainStream()
        onDispose {
            LogBroker.releaseStream()
        }
    }
    val settings by LiveLogOverlaySettings.state(context).collectAsStateWithLifecycle()
    val snapshot by LogBroker.snapshot.collectAsStateWithLifecycle()
    fun save(next: LiveLogOverlaySettingsData) = LiveLogOverlaySettings.write(context, next)
    val preview = remember(snapshot) { snapshot.takeLast(8) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("LIVE LOGS", color = RPCSXColors.primary)
        Text("Separate from the performance overlay. Hidden overlay still collects.", color = RPCSXColors.textSecondary, fontSize = 12.sp)
        SettingRow("Show overlay", settings.enabled) { save(settings.copy(enabled = it, locked = if (it) settings.locked else true, editMode = if (it) settings.editMode else false)) }
        SettingRow("Locked (touch-through)", settings.locked) { save(settings.copy(locked = it, editMode = if (it) false else settings.editMode)) }
        SettingRow("Edit position/size", settings.editMode) { save(settings.copy(editMode = it, locked = if (it) false else settings.locked)) }
        SettingRow("Autoscroll", settings.autoscroll) { save(settings.copy(autoscroll = it)) }
        SettingRow("Hide while menu open", settings.hideWithMenu) { save(settings.copy(hideWithMenu = it)) }
        Text("Opacity ${"%.2f".format(settings.opacity)}", color = RPCSXColors.textSecondary)
        Slider(value = settings.opacity, onValueChange = { save(settings.copy(opacity = it)) }, valueRange = 0.25f..1f)
        Text("Font ${"%.2f".format(settings.fontScale)}", color = RPCSXColors.textSecondary)
        Slider(value = settings.fontScale, onValueChange = { save(settings.copy(fontScale = it)) }, valueRange = 0.70f..1.50f)
        Text("Width ${"%.2f".format(settings.widthFraction)}", color = RPCSXColors.textSecondary)
        Slider(value = settings.widthFraction, onValueChange = { save(settings.copy(widthFraction = it)) }, valueRange = 0.30f..0.95f)
        Text("Height ${"%.2f".format(settings.heightFraction)}", color = RPCSXColors.textSecondary)
        Slider(value = settings.heightFraction, onValueChange = { save(settings.copy(heightFraction = it)) }, valueRange = 0.15f..0.80f)
        Text("Level", color = RPCSXColors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR).forEach { level ->
                FilterChip(
                    selected = settings.minLevel == level.name,
                    onClick = { save(settings.copy(minLevel = level.name)) },
                    label = { Text(level.name) },
                )
            }
        }
        Text("Source", color = RPCSXColors.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = settings.sourceFilter == "ALL", onClick = { save(settings.copy(sourceFilter = "ALL")) }, label = { Text("ALL") })
            FilterChip(selected = settings.sourceFilter == LogSourceKind.APP_ANDROID.name, onClick = { save(settings.copy(sourceFilter = LogSourceKind.APP_ANDROID.name)) }, label = { Text("APP") })
            FilterChip(selected = settings.sourceFilter == LogSourceKind.RPCSX_BACKEND.name, onClick = { save(settings.copy(sourceFilter = LogSourceKind.RPCSX_BACKEND.name)) }, label = { Text("RPCSX") })
        }
        HorizontalDivider()
        Text("PREVIEW", color = RPCSXColors.primary)
        Column(Modifier.fillMaxWidth().height(120.dp)) {
            if (preview.isEmpty()) {
                Text("Waiting for log lines…", color = RPCSXColors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                preview.forEach { entry ->
                    Text(
                        "${entry.tag ?: entry.source.name}: ${entry.message}",
                        color = RPCSXColors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { LiveLogOverlaySettings.reset(context) }) { Text("RESET LAYOUT") }
            TextButton(onClick = onBack) { Text("BACK") }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = RPCSXColors.textPrimary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun liveLogsBackIntent(): InGameMenuIntent = InGameMenuIntent.Back
