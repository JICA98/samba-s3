package com.zenithblue.sambas3.ui.controller

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ConnectedInputDevice
import com.zenithblue.sambas3.input.ControllerDeviceRepository
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerInputEvent
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.ControllerProfile
import com.zenithblue.sambas3.input.ControllerProfileRepository
import com.zenithblue.sambas3.input.ControllerProfileSelection
import com.zenithblue.sambas3.input.LogicalControl
import com.zenithblue.sambas3.input.MappingConflictResolver
import com.zenithblue.sambas3.input.RemapConflictAction
import kotlinx.coroutines.delay
import java.util.UUID

private const val UI_TAG = "S3PADUI"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ControllerSettingsScreen(
    navigateBack: () -> Unit,
    isInSplitPane: Boolean = false,
    onOpenTest: (ConnectedInputDevice) -> Unit = {},
) {
    val context = LocalContext.current
    val devicesRepo = remember(context) { ControllerDeviceRepository(context) }
    val devices by devicesRepo.devices.collectAsStateWithLifecycle()
    var selectedDeviceKey by remember { mutableStateOf<String?>(null) }
    val selectedDevice = devices.firstOrNull { it.deviceKey == selectedDeviceKey } ?: devices.firstOrNull()
    var profile by remember { mutableStateOf(ControllerProfileRepository.load()) }
    var selected by remember { mutableStateOf<LogicalControl?>(null) }
    var captureTarget by remember { mutableStateOf<LogicalControl?>(null) }
    var candidate by remember { mutableStateOf<ControllerInputEvent?>(null) }
    var migrationCandidate by remember { mutableStateOf<ControllerProfile?>(null) }
    var tab by remember { mutableStateOf("Mapping") }
    var liveState by remember { mutableStateOf(ControllerInputMonitor.state.value) }
    var pressedHotspots by remember { mutableStateOf(emptySet<String>()) }

    DisposableEffect(devicesRepo) {
        devicesRepo.start()
        Log.i(UI_TAG, "controls open")
        onDispose {
            ControllerInputMonitor.stopCaptureIfActive()
            devicesRepo.stop()
            Log.i(UI_TAG, "controls close")
        }
    }

    LaunchedEffect(selectedDevice?.deviceKey) {
        val device = selectedDevice
        if (device == null) {
            val fallback = ControllerProfileRepository.load()
            profile = fallback
            ControllerInputMonitor.reloadProfile(fallback)
            Log.i(UI_TAG, "no device selected; monitor uses current profile ${fallback.id}")
            return@LaunchedEffect
        }
        selectedDeviceKey = device.deviceKey
        val loaded = ControllerProfileRepository.loadForDevice(device)
        profile = loaded
        ControllerInputMonitor.reloadProfile(loaded)
        migrationCandidate = ControllerProfileRepository.loadAll().firstOrNull { stored ->
            stored.deviceKey == device.deviceKey && stored.family != null && stored.family != device.family && !stored.isDefault
        }
        Log.i(
            UI_TAG,
            "device selected ${device.name} family=${device.family} layout=${ControllerLayoutResolver.resolve(device.family).assetPath} profile=${loaded.id}",
        )
    }

    // Throttle live pad state so high-frequency samples do not recompose the whole tree every event.
    LaunchedEffect(Unit) {
        while (true) {
            delay(33L)
            val next = ControllerInputMonitor.state.value
            if (next != liveState) liveState = next
        }
    }

    LaunchedEffect(Unit) {
        ControllerInputMonitor.events.collect { event ->
            if (captureTarget != null && event.down) candidate = event
        }
    }

    LaunchedEffect(liveState, selectedDevice?.family, profile) {
        val family = selectedDevice?.family ?: ControllerFamily.GENERIC_GAMEPAD
        val lit = mutableSetOf<String>()
        LogicalControl.entries.forEach { control ->
            if (liveState.isPressed(control)) {
                lit += ControllerLayoutResolver.hotspotForLogical(control, family)
            }
        }
        pressedHotspots = lit
    }

    fun save(next: ControllerProfile) {
        val device = selectedDevice
        val tagged = if (device != null) {
            next.copy(
                deviceKey = device.deviceKey,
                deviceDescriptor = device.descriptor,
                vendorId = device.vendorId,
                productId = device.productId,
                family = device.family,
            )
        } else next
        profile = tagged
        ControllerProfileRepository.save(tagged)
        ControllerInputMonitor.reloadProfile(tagged)
        Log.i(UI_TAG, "profile saved ${tagged.id}")
    }

    fun beginCapture(logical: LogicalControl) {
        if (logical != LogicalControl.PS_HOME_FRONTEND) {
            selected = logical
            captureTarget = logical
            candidate = null
            selectedDevice?.let { device ->
                ControllerInputMonitor.startCapture(device.deviceKey, device.deviceId, logical, profile)
            }
            Log.i(UI_TAG, "remap capture ${logical.name}")
        }
    }

    fun finishCapture() {
        captureTarget = null
        candidate = null
        ControllerInputMonitor.stopSession()
    }

    fun applyRemap(action: RemapConflictAction) {
        val target = captureTarget ?: return
        val key = candidate?.keyCode ?: return
        val result = MappingConflictResolver.apply(profile.digitalBindings, target, key, action)
        if (result.applied) {
            save(profile.copy(digitalBindings = result.bindings, isDefault = false))
        }
        finishCapture()
    }

    val family = selectedDevice?.family ?: ControllerFamily.GENERIC_GAMEPAD
    val layout = remember(family) { ControllerLayoutResolver.resolve(family) }
    val leftStick = Offset(
        ((liveState.leftX - 127) / 127f).coerceIn(-1f, 1f),
        ((liveState.leftY - 127) / 127f).coerceIn(-1f, 1f),
    )
    val rightStick = Offset(
        ((liveState.rightX - 127) / 127f).coerceIn(-1f, 1f),
        ((liveState.rightY - 127) / 127f).coerceIn(-1f, 1f),
    )
    val leftTrigger = if (liveState.isPressed(LogicalControl.L2)) 1f else 0f
    val rightTrigger = if (liveState.isPressed(LogicalControl.R2)) 1f else 0f

    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .onPreviewKeyEvent { event ->
                    if (captureTarget != null && event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        candidate = ControllerInputEvent(event.nativeKeyEvent.keyCode, true)
                        true
                    } else false
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("CONTROLS", color = RPCSXColors.primary, style = MaterialTheme.typography.headlineSmall)
            DeviceStrip(
                devices = devices,
                selectedKey = selectedDevice?.deviceKey,
                profileName = profile.name,
                onSelect = {
                    ControllerInputMonitor.stopSession()
                    selectedDeviceKey = it.deviceKey
                },
            )
            selectedDevice?.let { device ->
                Text(
                    "${device.name} · ${device.family.name.replace('_', ' ')} · ${profile.name}",
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: Text("No gamepad or keyboard connected", color = RPCSXColors.textSecondary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Mapping", "Profiles", "Advanced").forEach { value ->
                    FilterChip(selected = tab == value, onClick = { tab = value }, label = { Text(value) })
                }
                selectedDevice?.let { device ->
                    Button(onClick = { onOpenTest(device) }) { Text("OPEN TEST") }
                }
            }

            when (tab) {
                "Profiles" -> ProfilesPane(profile, ::save)
                "Advanced" -> AdvancedPane(profile, ::save)
                else -> {
                    if (family == ControllerFamily.KEYBOARD) {
                        Row(
                            Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Keyboard detected", color = Color.White)
                                Text("PC Gamepad: WASD movement, arrows camera", color = RPCSXColors.textSecondary)
                            }
                            OutlinedButton(onClick = {
                                selectedDevice?.let { device ->
                                    save(ControllerProfileSelection.buildDefault(
                                        deviceKey = device.deviceKey,
                                        family = ControllerFamily.KEYBOARD,
                                        descriptor = device.descriptor,
                                        vendorId = device.vendorId,
                                        productId = device.productId,
                                    ))
                                }
                            }) { Text("APPLY PC GAMEPAD") }
                            OutlinedButton(onClick = {
                                selectedDevice?.let { device ->
                                    save(ControllerProfileSelection.buildDefault(
                                        deviceKey = device.deviceKey,
                                        family = ControllerFamily.KEYBOARD,
                                        descriptor = device.descriptor,
                                        vendorId = device.vendorId,
                                        productId = device.productId,
                                    ).copy(
                                        name = "D-pad Classic",
                                        digitalBindings = com.zenithblue.sambas3.input.FamilyDefaultMappings.keyboardDpadDefaults(),
                                        keyboardAnalog = null,
                                    ))
                                }
                            }) { Text("D-PAD CLASSIC") }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = true)) {
                        val landscape = maxWidth > 700.dp
                        if (landscape) {
                            Row(
                                Modifier.fillMaxWidth().fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ControllerFamilyVisual(
                                    family = family,
                                    layout = layout,
                                    state = liveState,
                                    pressedHotspots = pressedHotspots,
                                    selected = selected,
                                    leftStick = leftStick,
                                    rightStick = rightStick,
                                    leftTrigger = leftTrigger,
                                    rightTrigger = rightTrigger,
                                    onHotspotClick = { selected = it },
                                    onHotspotLongPress = ::beginCapture,
                                    modifier = Modifier.weight(1f).widthIn(min = 260.dp),
                                )
                                MappingList(
                                    profile = profile,
                                    state = liveState,
                                    selected = selected,
                                    captureTarget = captureTarget,
                                    family = family,
                                    onSelect = { selected = it },
                                    onRemap = ::beginCapture,
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    listFillsHeight = true,
                                )
                            }
                        } else {
                            Column(
                                Modifier.fillMaxWidth().fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ControllerFamilyVisual(
                                    family = family,
                                    layout = layout,
                                    state = liveState,
                                    pressedHotspots = pressedHotspots,
                                    selected = selected,
                                    leftStick = leftStick,
                                    rightStick = rightStick,
                                    leftTrigger = leftTrigger,
                                    rightTrigger = rightTrigger,
                                    onHotspotClick = { selected = it },
                                    onHotspotLongPress = ::beginCapture,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                MappingList(
                                    profile = profile,
                                    state = liveState,
                                    selected = selected,
                                    captureTarget = captureTarget,
                                    family = family,
                                    onSelect = { selected = it },
                                    onRemap = ::beginCapture,
                                    modifier = Modifier.fillMaxWidth().height(320.dp),
                                    listFillsHeight = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isInSplitPane) {
        Surface(color = RPCSXColors.surfaceElevated, modifier = Modifier.fillMaxSize().padding(18.dp)) { content() }
    } else {
        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = { Text("Controls") },
                    navigationIcon = { TextButton(onClick = navigateBack) { Text("BACK") } },
                )
            },
        ) { padding -> Box(Modifier.padding(padding)) { content() } }
    }

    val captured = candidate
    val target = captureTarget
    if (target != null && captured != null) {
        val conflict = MappingConflictResolver.findConflict(profile.digitalBindings, target, captured.keyCode)
        AlertDialog(
            onDismissRequest = ::finishCapture,
            title = { Text("REMAP ${target.label}") },
            text = {
                    Text(
                    "Use ${com.zenithblue.sambas3.input.PhysicalInputLabelFormatter.key(captured.keyCode)} for ${target.label}?" +
                        if (conflict.existing != null) " It is currently assigned to ${conflict.existing.label}." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { applyRemap(RemapConflictAction.REPLACE) }) { Text("REPLACE") }
            },
            dismissButton = {
                Row {
                    if (conflict.existing != null) {
                        TextButton(onClick = { applyRemap(RemapConflictAction.SWAP) }) { Text("SWAP") }
                    }
                    TextButton(onClick = { applyRemap(RemapConflictAction.CANCEL) }) { Text("CANCEL") }
                }
            },
        )
    }

    val stale = migrationCandidate
    val migrationDevice = selectedDevice
    if (stale != null && migrationDevice != null) {
        AlertDialog(
            onDismissRequest = { migrationCandidate = null },
            title = { Text("PROFILE FAMILY CHANGED") },
            text = {
                Text(
                    "This profile was created when ${migrationDevice.name} was detected as " +
                        "${stale.family?.name?.replace('_', ' ')}. Detected now: ${migrationDevice.family.name.replace('_', ' ')}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    save(ControllerProfileSelection.migrateLegacy(
                        legacy = stale,
                        deviceKey = migrationDevice.deviceKey,
                        family = migrationDevice.family,
                        descriptor = migrationDevice.descriptor,
                        vendorId = migrationDevice.vendorId,
                        productId = migrationDevice.productId,
                    ))
                    migrationCandidate = null
                }) { Text("MIGRATE TO ${migrationDevice.family.name.replace('_', ' ')}") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { migrationCandidate = null }) { Text("KEEP CURRENT") }
                    TextButton(onClick = {
                        save(ControllerProfileSelection.buildDefault(
                            deviceKey = migrationDevice.deviceKey,
                            family = migrationDevice.family,
                            descriptor = migrationDevice.descriptor,
                            vendorId = migrationDevice.vendorId,
                            productId = migrationDevice.productId,
                        ))
                        migrationCandidate = null
                    }) { Text("RESET") }
                }
            },
        )
    }
}

@Composable
private fun DeviceStrip(
    devices: List<ConnectedInputDevice>,
    selectedKey: String?,
    profileName: String,
    onSelect: (ConnectedInputDevice) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (devices.isEmpty()) {
            FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("No devices") })
        } else {
            devices.forEach { device ->
                FilterChip(
                    selected = device.deviceKey == selectedKey,
                    onClick = { onSelect(device) },
                    label = {
                        Column {
                            Text(device.name.take(28))
                            Text(
                                "${device.family.name.take(12)} · $profileName",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MappingList(
    profile: ControllerProfile,
    state: com.zenithblue.sambas3.input.LogicalPadState,
    selected: LogicalControl?,
    captureTarget: LogicalControl?,
    family: ControllerFamily,
    onSelect: (LogicalControl) -> Unit,
    onRemap: (LogicalControl) -> Unit,
    modifier: Modifier = Modifier,
    listFillsHeight: Boolean = false,
) {
    Column(modifier) {
        if (family == ControllerFamily.KEYBOARD) {
            Text("KEYBOARD · ${profile.name}", color = RPCSXColors.primary)
            Text("PC Gamepad · WASD left stick · Arrow keys right stick", color = RPCSXColors.textSecondary)
            profile.keyboardAnalog?.let { analog ->
                Text(
                    "MOVEMENT  ${keyPair(analog.leftX)} horizontal · ${keyPair(analog.leftY)} vertical",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "CAMERA  ${keyPair(analog.rightX)} horizontal · ${keyPair(analog.rightY)} vertical",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            if (captureTarget == null) "Tap to inspect · hold to remap" else "Press a physical input for ${captureTarget.label}",
            color = RPCSXColors.textSecondary,
        )
        val listModifier = if (listFillsHeight) Modifier.weight(1f).fillMaxHeight() else Modifier.height(360.dp)
        LazyColumn(listModifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(LogicalControl.entries, key = { it.name }) { logical ->
                val highlighted = selected == logical || state.isPressed(logical)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (highlighted) Color(0x553AD69B) else RPCSXColors.surface)
                        .combinedClickable(onClick = { onSelect(logical) }, onLongClick = { onRemap(logical) })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(logical.label, color = Color.White)
                        Text(
                            if (logical == LogicalControl.PS_HOME_FRONTEND) "Esc · Emulator Menu"
                            else com.zenithblue.sambas3.input.PhysicalInputLabelFormatter.key(profile.digitalBindings[logical]),
                            color = RPCSXColors.textSecondary,
                        )
                    }
                    if (logical == LogicalControl.PS_HOME_FRONTEND) Text("RESERVED", color = RPCSXColors.primary)
                }
            }
        }
    }
}

private fun keyPair(pair: com.zenithblue.sambas3.input.DigitalAxisPair): String =
    "${com.zenithblue.sambas3.input.PhysicalInputLabelFormatter.key(pair.negativeKey)}/" +
        com.zenithblue.sambas3.input.PhysicalInputLabelFormatter.key(pair.positiveKey)

@Composable
private fun ProfilesPane(profile: ControllerProfile, save: (ControllerProfile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("PROFILES", color = RPCSXColors.primary, style = MaterialTheme.typography.titleMedium)
        ControllerProfileRepository.loadAll().forEach { stored ->
            Row(
                Modifier.fillMaxWidth().background(RPCSXColors.surface).padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(stored.name, color = Color.White)
                    Text(
                        listOfNotNull(stored.id, stored.family?.name).joinToString(" · "),
                        color = RPCSXColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row {
                    TextButton(onClick = { save(stored) }) { Text("LOAD") }
                    if (!stored.isDefault && stored.id != "default") {
                        TextButton(onClick = { ControllerProfileRepository.delete(stored.id); save(ControllerProfileRepository.load()) }) {
                            Text("DELETE")
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    save(
                        profile.copy(
                            id = UUID.randomUUID().toString(),
                            name = "Profile ${ControllerProfileRepository.loadAll().size + 1}",
                            isDefault = false,
                        ),
                    )
                },
            ) { Text("SAVE AS") }
            OutlinedButton(
                onClick = {
                    val deviceKey = profile.deviceKey
                    val family = profile.family ?: ControllerFamily.GENERIC_GAMEPAD
                    save(
                        ControllerProfileSelection.buildDefault(
                            deviceKey = deviceKey,
                            family = family,
                            descriptor = profile.deviceDescriptor,
                            vendorId = profile.vendorId,
                            productId = profile.productId,
                        ),
                    )
                },
            ) { Text("RESET DEFAULT") }
            OutlinedButton(
                onClick = {
                    save(profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} Copy", isDefault = false))
                },
            ) { Text("DUPLICATE") }
        }
    }
}

@Composable
private fun AdvancedPane(profile: ControllerProfile, save: (ControllerProfile) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("ADVANCED", color = RPCSXColors.primary, style = MaterialTheme.typography.titleMedium)
        Text(
            "Player assignment is omitted — the emulator overlay pad path only drives player 0.",
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("DEVICE DIAGNOSTICS", color = RPCSXColors.primary, style = MaterialTheme.typography.titleSmall)
        Text(
            "family=${profile.family?.name ?: "unknown"}  VID=${profile.vendorId ?: 0}  PID=${profile.productId ?: 0}\n" +
                "descriptor=${profile.deviceDescriptor ?: "none"}",
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Tuning("LEFT DEADZONE", profile.leftStick.deadzone, 0f..0.5f) {
            save(profile.copy(leftStick = profile.leftStick.copy(deadzone = it), isDefault = false))
        }
        Tuning("LEFT SENSITIVITY", profile.leftStick.sensitivity, 0.25f..3f) {
            save(profile.copy(leftStick = profile.leftStick.copy(sensitivity = it), isDefault = false))
        }
        Tuning("RIGHT DEADZONE", profile.rightStick.deadzone, 0f..0.5f) {
            save(profile.copy(rightStick = profile.rightStick.copy(deadzone = it), isDefault = false))
        }
        Tuning("RIGHT SENSITIVITY", profile.rightStick.sensitivity, 0.25f..3f) {
            save(profile.copy(rightStick = profile.rightStick.copy(sensitivity = it), isDefault = false))
        }
        Tuning("TRIGGER THRESHOLD", profile.triggerThreshold, 0.05f..0.9f) {
            save(profile.copy(triggerThreshold = it, isDefault = false))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = profile.leftStick.invertX,
                onClick = {
                    save(profile.copy(leftStick = profile.leftStick.copy(invertX = !profile.leftStick.invertX), isDefault = false))
                },
                label = { Text("Invert LX") },
            )
            FilterChip(
                selected = profile.leftStick.invertY,
                onClick = {
                    save(profile.copy(leftStick = profile.leftStick.copy(invertY = !profile.leftStick.invertY), isDefault = false))
                },
                label = { Text("Invert LY") },
            )
            FilterChip(
                selected = profile.rightStick.invertX,
                onClick = {
                    save(profile.copy(rightStick = profile.rightStick.copy(invertX = !profile.rightStick.invertX), isDefault = false))
                },
                label = { Text("Invert RX") },
            )
            FilterChip(
                selected = profile.rightStick.invertY,
                onClick = {
                    save(profile.copy(rightStick = profile.rightStick.copy(invertY = !profile.rightStick.invertY), isDefault = false))
                },
                label = { Text("Invert RY") },
            )
        }
    }
}

@Composable
private fun StickMeters(left: Offset, right: Offset, lt: Float, rt: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("LS (${"%.2f".format(left.x)}, ${"%.2f".format(left.y)})", color = Color.White)
        Text("RS (${"%.2f".format(right.x)}, ${"%.2f".format(right.y)})", color = Color.White)
        Text("LT ${"%.0f".format(lt * 100)}%  RT ${"%.0f".format(rt * 100)}%", color = Color.White)
    }
}

@Composable
private fun Tuning(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$title  ${"%.2f".format(value)}", color = Color.White)
        Slider(value, onChange, valueRange = range)
    }
}
