package com.zenithblue.sambas3.ui.controller

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ConnectedInputDevice
import com.zenithblue.sambas3.input.ControllerDeviceRepository
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.ControllerProfile
import com.zenithblue.sambas3.input.ControllerProfileRepository
import com.zenithblue.sambas3.input.KeyboardKeyVisualRegistry
import com.zenithblue.sambas3.input.LogicalControl
import com.zenithblue.sambas3.input.StartHoldTracker
import kotlinx.coroutines.delay

@Composable
fun ControllerTestScreen(
    deviceKey: String,
    navigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val devicesRepo = remember(context) { ControllerDeviceRepository(context) }
    val devices by devicesRepo.devices.collectAsStateWithLifecycle()
    val selectedDevice = devices.firstOrNull { it.deviceKey == deviceKey }
    var profile by remember { mutableStateOf<ControllerProfile?>(null) }
    val testState by ControllerInputMonitor.testState.collectAsStateWithLifecycle()
    var startProgress by remember { mutableFloatStateOf(0f) }
    var backHintNonce by remember { mutableIntStateOf(0) }
    var exited by remember { mutableStateOf(false) }
    val startTracker = remember { StartHoldTracker() }
    val startDown = testState.pressedLogicalControls.contains(LogicalControl.START)
    val closeTest: () -> Unit = {
        if (!exited) {
            exited = true
            ControllerInputMonitor.stopSession()
            navigateBack()
        }
    }

    DisposableEffect(devicesRepo) {
        devicesRepo.start()
        onDispose {
            ControllerInputMonitor.stopSession()
            devicesRepo.stop()
        }
    }

    LaunchedEffect(selectedDevice?.deviceId, deviceKey) {
        val device = selectedDevice
        if (device == null) {
            profile = null
            ControllerInputMonitor.stopSession()
        } else {
            val loaded = ControllerProfileRepository.loadForDevice(device)
            profile = loaded
            ControllerInputMonitor.startTest(device.deviceKey, device.deviceId, loaded)
        }
    }

    LaunchedEffect(startDown) {
        if (!startDown) {
            startProgress = startTracker.update(false, SystemClock.elapsedRealtime()).progress
            return@LaunchedEffect
        }
        while (true) {
            val result = startTracker.update(true, SystemClock.elapsedRealtime())
            startProgress = result.progress
            if (result.completed && !exited) {
                closeTest()
                break
            }
            delay(50L)
        }
    }

    BackHandler {
        // Test mode deliberately has one physical exit gesture. Android Back only explains it.
        backHintNonce++
    }

    val device = selectedDevice
    val currentProfile = profile
    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("TEST", color = RPCSXColors.primary, style = MaterialTheme.typography.headlineSmall)
                    Text(device?.name ?: "Device disconnected", color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Selected device only", color = RPCSXColors.textSecondary)
                    TextButton(
                        onClick = closeTest,
                        modifier = Modifier.focusProperties { canFocus = false },
                    ) {
                        Text("CLOSE")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (device == null || currentProfile == null) {
                Text("Device disconnected", color = RPCSXColors.errorColor)
                Text("Reconnect the selected device to resume testing.", color = RPCSXColors.textSecondary)
                OutlinedButton(onClick = closeTest) { Text("RETURN TO CONTROLS") }
                return@Column
            }

            val family = device.family
            val layout = remember(family) { ControllerLayoutResolver.resolve(family) }
            val pad = testState.pad
            val leftStick = Offset((pad.leftX - 127) / 127f, (pad.leftY - 127) / 127f)
            val rightStick = Offset((pad.rightX - 127) / 127f, (pad.rightY - 127) / 127f)
            val hotspots = remember(testState, family) {
                testHotspots(device, testState.pressedLogicalControls, testState.pressedPhysicalKeys, pad)
            }
            Row(
                Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE CONTROLLER", color = RPCSXColors.primary, style = MaterialTheme.typography.labelLarge)
                    Text(currentProfile.name, color = Color.White)
                }
                Text(
                    if (family == ControllerFamily.KEYBOARD) "Keyboard input" else "Physical controller",
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).background(RPCSXColors.surface),
                contentAlignment = Alignment.Center,
            ) {
                ControllerFamilyVisual(
                    family = family,
                    layout = layout,
                    state = pad,
                    pressedHotspots = hotspots,
                    selected = null,
                    leftStick = leftStick,
                    rightStick = rightStick,
                    leftTrigger = pad.leftTrigger,
                    rightTrigger = pad.rightTrigger,
                    onHotspotClick = {},
                    onHotspotLongPress = {},
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                )
            }
            if (family == ControllerFamily.KEYBOARD) {
                Text("WASD → Left Stick     Arrows → Right Stick     J/K/U/I → Face", color = Color.White)
            }
            Row(
                Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TestMetric("LEFT STICK", "${"%.2f".format(leftStick.x)},${"%.2f".format(leftStick.y)}")
                TestMetric("RIGHT STICK", "${"%.2f".format(rightStick.x)},${"%.2f".format(rightStick.y)}")
                TestMetric("L2", "${"%.0f".format(pad.leftTrigger * 100)}%")
                TestMetric("R2", "${"%.0f".format(pad.rightTrigger * 100)}%")
            }
            Column(
                Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text("LAST INPUT", color = RPCSXColors.primary, style = MaterialTheme.typography.labelSmall)
                Text(
                    testState.lastInput?.let { "${it.physical} → ${it.action}" } ?: "No input yet",
                    color = Color.White,
                )
                testState.unmappedPhysicalInputs.firstOrNull()?.let {
                    Text("UNMAPPED INPUT  ${it.label}", color = RPCSXColors.errorColor)
                }
            }
            if (startDown) {
                Text("EXIT TEST  ${"%.1f".format(startProgress * 2f)} / 2.0 s · Release to cancel", color = RPCSXColors.primary)
                LinearProgressIndicator(
                    progress = { startProgress },
                    Modifier.fillMaxWidth(),
                    color = RPCSXColors.primary,
                    trackColor = RPCSXColors.surfaceOverlay,
                )
            } else {
                Text("Hold ${if (family == ControllerFamily.KEYBOARD) "ENTER (START)" else "START"} for 2 seconds to exit", color = RPCSXColors.textSecondary)
                LinearProgressIndicator(
                    progress = { 0f },
                    Modifier.fillMaxWidth(),
                    color = RPCSXColors.primary,
                    trackColor = RPCSXColors.surfaceOverlay,
                )
            }
            if (backHintNonce > 0) Text("Back is disabled here. Hold START for 2 seconds or tap CLOSE.", color = RPCSXColors.textSecondary)
        }
    }
}

@Composable
private fun TestMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

private fun testHotspots(
    device: ConnectedInputDevice,
    logical: Set<LogicalControl>,
    physicalKeys: Set<Int>,
    pad: com.zenithblue.sambas3.input.LogicalPadState,
): Set<String> = buildSet {
    logical.forEach { add(ControllerLayoutResolver.hotspotForLogical(it, device.family)) }
    if (device.family == ControllerFamily.KEYBOARD) physicalKeys.forEach {
        KeyboardKeyVisualRegistry.hotspotForKey(it)?.let(::add)
    }
    if (kotlin.math.abs(pad.leftX - 127) > 4 || kotlin.math.abs(pad.leftY - 127) > 4) add("stick_left")
    if (kotlin.math.abs(pad.rightX - 127) > 4 || kotlin.math.abs(pad.rightY - 127) > 4) add("stick_right")
    if (pad.leftTrigger > .02f) add("btn_l2")
    if (pad.rightTrigger > .02f) add("btn_r2")
}
