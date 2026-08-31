package com.zenithblue.sambas3.ui.controller

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ControllerDeviceRepository
import com.zenithblue.sambas3.input.ControllerInputEvent
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.input.ControllerMonitorMode
import com.zenithblue.sambas3.input.ControllerProfile
import com.zenithblue.sambas3.input.ControllerProfileRepository
import com.zenithblue.sambas3.input.LogicalControl
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettingsScreen(navigateBack: () -> Unit, isInSplitPane: Boolean = false) {
    val context = LocalContext.current
    val devicesRepo = remember(context) { ControllerDeviceRepository(context) }
    val devices by devicesRepo.devices.collectAsStateWithLifecycle()
    var profile by remember { mutableStateOf(ControllerProfileRepository.load()) }
    var selected by remember { mutableStateOf<LogicalControl?>(null) }
    var captureTarget by remember { mutableStateOf<LogicalControl?>(null) }
    var candidate by remember { mutableStateOf<ControllerInputEvent?>(null) }
    var tab by remember { mutableStateOf("Mapping") }
    val state by ControllerInputMonitor.state.collectAsStateWithLifecycle()

    DisposableEffect(devicesRepo) {
        devicesRepo.start()
        ControllerInputMonitor.setMode(ControllerMonitorMode.Test)
        onDispose {
            ControllerInputMonitor.setMode(ControllerMonitorMode.Off)
            devicesRepo.stop()
        }
    }
    LaunchedEffect(Unit) {
        ControllerInputMonitor.events.collect { event ->
            if (captureTarget != null && event.down) candidate = event
        }
    }

    fun save(next: ControllerProfile) {
        profile = next
        ControllerProfileRepository.save(next)
        ControllerInputMonitor.reloadProfile()
    }
    fun beginCapture(logical: LogicalControl) {
        if (logical != LogicalControl.PS_HOME_FRONTEND) {
            selected = logical
            captureTarget = logical
            candidate = null
            ControllerInputMonitor.setMode(ControllerMonitorMode.Capture)
        }
    }
    fun finishCapture() {
        captureTarget = null
        candidate = null
        ControllerInputMonitor.setMode(ControllerMonitorMode.Test)
    }

    val content: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize().padding(16.dp)
                .onPreviewKeyEvent { event ->
                    if (captureTarget != null && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        candidate = ControllerInputEvent(event.nativeKeyEvent.keyCode, true)
                        true
                    } else false
                }, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("CONTROLLER SETTINGS", color = RPCSXColors.primary, style = MaterialTheme.typography.headlineSmall)
                    Text(devices.firstOrNull()?.name ?: "No gamepad connected", color = RPCSXColors.textSecondary)
                }
                OutlinedButton(onClick = { save(ControllerProfileRepository.default()) }) { Text("RESET") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Mapping", "Test", "Profiles").forEach { value -> FilterChip(selected = tab == value, onClick = { tab = value }, label = { Text(value) }) } }
            if (tab == "Profiles") {
                Text("PROFILES", color = RPCSXColors.primary, style = MaterialTheme.typography.titleMedium)
                ControllerProfileRepository.loadAll().forEach { stored ->
                    Row(Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text(stored.name, color = Color.White); Text(stored.id, color = RPCSXColors.textSecondary) }
                        TextButton(onClick = { save(stored) }) { Text("LOAD") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save(profile.copy(id = UUID.randomUUID().toString(), name = "Profile ${ControllerProfileRepository.loadAll().size + 1}")) }) { Text("SAVE AS") }
                    OutlinedButton(onClick = { save(ControllerProfileRepository.default()) }) { Text("RESET DEFAULT") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ControllerSchematic(state, selected, { selected = it }, ::beginCapture, Modifier.weight(1f).widthIn(min = 260.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (captureTarget == null) "Tap to inspect · hold to remap" else "Press a physical button for ${captureTarget!!.label}", color = RPCSXColors.textSecondary)
                        LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(LogicalControl.entries, key = { it.name }) { logical ->
                                val physical = profile.digitalBindings[logical]?.let(KeyEvent::keyCodeToString) ?: if (logical == LogicalControl.PS_HOME_FRONTEND) "Reserved · Emulator Menu" else "Unassigned"
                                val highlighted = selected == logical || state.isPressed(logical)
                                Row(
                                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (highlighted) Color(0x553AD69B) else RPCSXColors.surface)
                                        .combinedClickable(onClick = { selected = logical }, onLongClick = { beginCapture(logical) }).padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) { Text(logical.label, color = Color.White); Text(physical, color = RPCSXColors.textSecondary) }
                                    if (logical == LogicalControl.PS_HOME_FRONTEND) Text("RESERVED", color = RPCSXColors.primary)
                                }
                            }
                        }
                    }
                }
                if (tab == "Test") Text("LIVE  ${state.leftX},${state.leftY}  ·  ${state.rightX},${state.rightY}  ·  d1=${state.digital1} d2=${state.digital2}", color = RPCSXColors.primary)
                Tuning("LEFT STICK", profile.leftStick.deadzone) { save(profile.copy(leftStick = profile.leftStick.copy(deadzone = it))) }
                Tuning("RIGHT STICK", profile.rightStick.deadzone) { save(profile.copy(rightStick = profile.rightStick.copy(deadzone = it))) }
            }
        }
    }
    if (isInSplitPane) Surface(color = RPCSXColors.surfaceElevated, modifier = Modifier.fillMaxSize().padding(18.dp)) { content() }
    else Scaffold(topBar = { LargeTopAppBar(title = { Text("Controller") }, navigationIcon = { TextButton(onClick = navigateBack) { Text("BACK") } }) }) { padding -> Box(Modifier.padding(padding)) { content() } }

    val captured = candidate
    val target = captureTarget
    if (target != null && captured != null) {
        val conflict = profile.digitalBindings.entries.firstOrNull { it.value == captured.keyCode }?.key
        AlertDialog(
            onDismissRequest = ::finishCapture,
            title = { Text("REMAP ${target.label}") },
            text = { Text("Use ${KeyEvent.keyCodeToString(captured.keyCode)} for ${target.label}?" + if (conflict != null && conflict != target) " It is currently assigned to ${conflict.label}." else "") },
            confirmButton = { TextButton(onClick = { save(profile.copy(digitalBindings = profile.digitalBindings + (target to captured.keyCode))); finishCapture() }) { Text("CONFIRM") } },
            dismissButton = { Row { if (conflict != null && conflict != target) TextButton(onClick = { save(profile.copy(digitalBindings = profile.digitalBindings - conflict + (target to captured.keyCode))); finishCapture() }) { Text("SWAP") }; TextButton(onClick = ::finishCapture) { Text("CANCEL") } } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControllerSchematic(state: com.zenithblue.sambas3.input.LogicalPadState, selected: LogicalControl?, onClick: (LogicalControl) -> Unit, onLongPress: (LogicalControl) -> Unit, modifier: Modifier) {
    Surface(modifier = modifier, color = Color(0xFF10151D), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GAMEPAD MAP", color = RPCSXColors.primary, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf(LogicalControl.L2, LogicalControl.L1, LogicalControl.R1, LogicalControl.R2).forEach { Hotspot(it, state, selected, onClick, onLongPress) } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { listOf(LogicalControl.DPAD_UP, LogicalControl.DPAD_LEFT, LogicalControl.DPAD_RIGHT, LogicalControl.DPAD_DOWN).forEach { Hotspot(it, state, selected, onClick, onLongPress) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { listOf(LogicalControl.SELECT, LogicalControl.START, LogicalControl.PS_HOME_FRONTEND).forEach { Hotspot(it, state, selected, onClick, onLongPress) } }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { listOf(LogicalControl.TRIANGLE, LogicalControl.SQUARE, LogicalControl.CIRCLE, LogicalControl.CROSS).forEach { Hotspot(it, state, selected, onClick, onLongPress) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { listOf(LogicalControl.L3, LogicalControl.R3).forEach { Hotspot(it, state, selected, onClick, onLongPress) } }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hotspot(control: LogicalControl, state: com.zenithblue.sambas3.input.LogicalPadState, selected: LogicalControl?, onClick: (LogicalControl) -> Unit, onLongPress: (LogicalControl) -> Unit) {
    Text(control.label.replace("D-pad ", ""), color = if (selected == control || state.isPressed(control)) RPCSXColors.primary else Color.White, modifier = Modifier.padding(2.dp).background(if (state.isPressed(control)) Color(0x6643D69B) else Color(0x332B3746)).combinedClickable(onClick = { onClick(control) }, onLongClick = { onLongPress(control) }).padding(horizontal = 7.dp, vertical = 5.dp))
}

@Composable private fun Tuning(title: String, deadzone: Float, onChange: (Float) -> Unit) {
    Column { Text("$title  ${(deadzone * 100).toInt()}%", color = Color.White); Slider(deadzone, onChange, valueRange = 0f..0.5f) }
}

private fun com.zenithblue.sambas3.input.LogicalPadState.isPressed(control: LogicalControl): Boolean = if (control.bank == 0) digital1 and control.bit != 0 else digital2 and control.bit != 0
