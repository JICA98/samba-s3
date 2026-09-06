package com.zenithblue.sambas3.ui.logging

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val preview = remember(snapshot) { snapshot.takeLast(16) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        // Top Bar
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "LIVE LOGS",
                    color = RPCSXColors.primary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Separate from performance overlay. Hidden overlay still collects.",
                    color = RPCSXColors.textSecondary,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { LiveLogOverlaySettings.reset(context) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("RESET LAYOUT", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                    color = RPCSXColors.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Text("BACK", color = RPCSXColors.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 21:9 Split view: Controls on left, Live Terminal on right
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Column: Configuration (scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
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
                        SettingRow("Show overlay", settings.enabled) { save(settings.copy(enabled = it, locked = if (it) settings.locked else true, editMode = if (it) settings.editMode else false)) }
                        SettingRow("Locked (touch-through)", settings.locked) { save(settings.copy(locked = it, editMode = if (it) false else settings.editMode)) }
                        SettingRow("Edit position/size", settings.editMode) { save(settings.copy(editMode = it, locked = if (it) false else settings.locked)) }
                        SettingRow("Autoscroll", settings.autoscroll) { save(settings.copy(autoscroll = it)) }
                        SettingRow("Hide while menu open", settings.hideWithMenu) { save(settings.copy(hideWithMenu = it)) }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x28FFFFFF),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Opacity: ${(settings.opacity * 100).toInt()}%", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(value = settings.opacity, onValueChange = { save(settings.copy(opacity = it)) }, valueRange = 0.25f..1f)

                        Text("Font scale: ${"%.2f".format(settings.fontScale)}x", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(value = settings.fontScale, onValueChange = { save(settings.copy(fontScale = it)) }, valueRange = 0.70f..1.50f)

                        Text("Width: ${(settings.widthFraction * 100).toInt()}%", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(value = settings.widthFraction, onValueChange = { save(settings.copy(widthFraction = it)) }, valueRange = 0.30f..0.95f)

                        Text("Height: ${(settings.heightFraction * 100).toInt()}%", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelMedium)
                        Slider(value = settings.heightFraction, onValueChange = { save(settings.copy(heightFraction = it)) }, valueRange = 0.15f..0.80f)
                    }
                }

                Text("Level", color = RPCSXColors.textPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR).forEach { level ->
                        FilterChip(
                            selected = settings.minLevel == level.name,
                            onClick = { save(settings.copy(minLevel = level.name)) },
                            label = { Text(level.name) },
                        )
                    }
                }

                Text("Source", color = RPCSXColors.textPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = settings.sourceFilter == "ALL", onClick = { save(settings.copy(sourceFilter = "ALL")) }, label = { Text("ALL") })
                    FilterChip(selected = settings.sourceFilter == LogSourceKind.APP_ANDROID.name, onClick = { save(settings.copy(sourceFilter = LogSourceKind.APP_ANDROID.name)) }, label = { Text("APP") })
                    FilterChip(selected = settings.sourceFilter == LogSourceKind.RPCSX_BACKEND.name, onClick = { save(settings.copy(sourceFilter = LogSourceKind.RPCSX_BACKEND.name)) }, label = { Text("RPCSX") })
                }
            }

            // Right Column: Live Terminal Stream
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "LIVE TERMINAL STREAM",
                    color = RPCSXColors.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEB090C13),
                    border = BorderStroke(1.dp, Color(0x33FFFFFF)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (preview.isEmpty()) {
                            Text(
                                "Waiting for log lines…",
                                color = RPCSXColors.textSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        } else {
                            preview.forEach { entry ->
                                val tagColor = when (entry.level) {
                                    LogLevel.ERROR -> RPCSXColors.errorColor
                                    LogLevel.WARN -> RPCSXColors.focusRing
                                    else -> RPCSXColors.primary
                                }
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        text = "[${entry.tag ?: entry.source.name}] ",
                                        color = tagColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = entry.message,
                                        color = RPCSXColors.textPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = RPCSXColors.textPrimary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun liveLogsBackIntent(): InGameMenuIntent = InGameMenuIntent.Back
