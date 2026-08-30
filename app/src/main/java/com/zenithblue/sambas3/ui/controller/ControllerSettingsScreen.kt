package com.zenithblue.sambas3.ui.controller

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.input.ControllerProfile
import com.zenithblue.sambas3.input.ControllerProfileRepository
import com.zenithblue.sambas3.input.GamepadMapper
import com.zenithblue.sambas3.input.LogicalControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettingsScreen(navigateBack: () -> Unit, isInSplitPane: Boolean = false) {
    val requester = remember { FocusRequester() }
    var profile by remember { mutableStateOf(ControllerProfileRepository.load()) }
    var selected by remember { mutableStateOf<LogicalControl?>(null) }
    var tab by remember { mutableStateOf("Mapping") }
    val state by ControllerInputMonitor.state.collectAsStateWithLifecycle()
    val mapper = remember(profile) { GamepadMapper(profile) }
    val devices = remember { InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { device -> (device.sources and InputDevice.SOURCE_GAMEPAD) != 0 || (device.sources and InputDevice.SOURCE_JOYSTICK) != 0 } }
    fun save(next: ControllerProfile) { profile = next; ControllerProfileRepository.save(next); ControllerInputMonitor.reloadProfile() }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().padding(16.dp).onPreviewKeyEvent { event ->
            val logical = selected
            if (logical != null && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                if (logical != LogicalControl.PS_HOME_FRONTEND && event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_HOME && event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BUTTON_MODE) {
                    save(profile.copy(digitalBindings = profile.digitalBindings + (logical to event.nativeKeyEvent.keyCode)))
                    selected = null
                }
                true
            } else false
        }.focusRequester(requester).focusable(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("CONTROLLER MAPPING", color = RPCSXColors.primary, style = MaterialTheme.typography.headlineSmall); Text(if (devices.isEmpty()) "No controller detected · keyboard/default profile" else devices.joinToString { it.name }, color = RPCSXColors.textSecondary) }
                OutlinedButton(onClick = { save(ControllerProfileRepository.default()) }) { Text("RESET") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Mapping", "Test", "Profiles").forEach { FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(it) }) } }
            if (tab == "Profiles") {
                Text("ACTIVE PROFILE  ${profile.name}", color = Color.White)
                Text("Mappings are stored in a versioned profile and preserve legacy input_bindings.", color = RPCSXColors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { save(profile.copy(name = "Default")) }) { Text("SAVE PROFILE") }; OutlinedButton(onClick = { profile = ControllerProfileRepository.load() }) { Text("LOAD") } }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ControllerDiagram(state, Modifier.weight(1f).aspectRatio(1.7f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (selected == null) "Select a logical control to remap" else "Press a physical key for ${selected!!.label}" , color = RPCSXColors.textSecondary)
                        LazyColumn(Modifier.height(330.dp)) {
                            items(LogicalControl.entries, key = { it.name }) { logical ->
                                val physical = profile.digitalBindings[logical]?.let { key -> KeyEvent.keyCodeToString(key) } ?: if (logical == LogicalControl.PS_HOME_FRONTEND) "Reserved · Emulator Menu" else "Unassigned"
                                Row(Modifier.fillMaxWidth().background(if (state.isPressed(logical)) Color(0x553AD69B) else RPCSXColors.surface).border(1.dp, RPCSXColors.outlineVariant).padding(9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column { Text(logical.label, color = Color.White); Text(physical, color = RPCSXColors.textSecondary) }
                                    if (logical == LogicalControl.PS_HOME_FRONTEND) Text("RESERVED", color = RPCSXColors.primary) else TextButton(onClick = { selected = logical; requester.requestFocus() }) { Text("REMAP") }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tuning("LEFT STICK", profile.leftStick.deadzone, { save(profile.copy(leftStick = profile.leftStick.copy(deadzone = it))) })
                    Tuning("RIGHT STICK", profile.rightStick.deadzone, { save(profile.copy(rightStick = profile.rightStick.copy(deadzone = it))) })
                }
                if (tab == "Test") Text("Live state  L ${state.leftX},${state.leftY}  R ${state.rightX},${state.rightY}  d1=${state.digital1} d2=${state.digital2}", color = RPCSXColors.primary)
            }
        }
    }
    if (isInSplitPane) content() else Scaffold(topBar = { LargeTopAppBar(title = { Text("Controller") }, navigationIcon = { TextButton(onClick = navigateBack) { Text("BACK") } }) }) { padding -> Box(Modifier.padding(padding)) { content() } }
}

@Composable private fun ControllerDiagram(state: com.zenithblue.sambas3.input.LogicalPadState, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF10151D)).padding(16.dp)) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        drawOval(Color(0xFF222A35), androidx.compose.ui.geometry.Offset(size.width * .16f, size.height * .18f), androidx.compose.ui.geometry.Size(size.width * .68f, size.height * .64f))
        drawCircle(if (state.isPressed(LogicalControl.CROSS)) Color(0xFF73E6B5) else Color(0xFF425060), center = center.copy(x = size.width * .72f, y = size.height * .42f), radius = size.minDimension * .045f)
        drawCircle(if (state.isPressed(LogicalControl.CIRCLE)) Color(0xFFFF8A9A) else Color(0xFF425060), center = center.copy(x = size.width * .78f, y = size.height * .50f), radius = size.minDimension * .045f)
        drawCircle(if (state.isPressed(LogicalControl.SQUARE)) Color(0xFF81AFFF) else Color(0xFF425060), center = center.copy(x = size.width * .66f, y = size.height * .50f), radius = size.minDimension * .045f)
        drawCircle(if (state.isPressed(LogicalControl.TRIANGLE)) Color(0xFFFFD166) else Color(0xFF425060), center = center.copy(x = size.width * .72f, y = size.height * .58f), radius = size.minDimension * .045f)
        drawCircle(Color(0xFF73E6B5), center = center.copy(x = size.width * (.30f + state.leftX / 255f * .12f), y = size.height * (.46f + state.leftY / 255f * .12f)), radius = size.minDimension * .06f)
        drawCircle(Color(0xFF73E6B5), center = center.copy(x = size.width * (.58f + state.rightX / 255f * .12f), y = size.height * (.46f + state.rightY / 255f * .12f)), radius = size.minDimension * .06f)
    }
}

@Composable private fun Tuning(title: String, deadzone: Float, onChange: (Float) -> Unit) {
    Column(Modifier.width(220.dp)) { Text(title, color = Color.White); Text("Deadzone ${(deadzone * 100).toInt()}%", color = RPCSXColors.textSecondary); Slider(deadzone, onChange, valueRange = 0f..0.5f) }
}

private fun com.zenithblue.sambas3.input.LogicalPadState.isPressed(control: LogicalControl): Boolean = if (control.bank == 0) digital1 and control.bit != 0 else digital2 and control.bit != 0
