package com.zenithblue.sambas3.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugControllerScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current
    var lastInject by remember { mutableStateOf("none") }
    var padHint by remember { mutableStateOf("x=1632 y=873 for 1920×1080 (see PadOverlay.kt:172)") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DEBUG — CONTROLLER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) { Icon(painterResource(R.drawable.ic_keyboard_arrow_left), null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RPCSXColors.background, titleContentColor = RPCSXColors.primary)
            )
        },
        containerColor = RPCSXColors.background
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Agent ADB bridge (no taps) → PPU-safe 120ms pulse. Use in loops instead of coordinate calc.", color = RPCSXColors.textSecondary, fontSize = 12.sp)
            Card(colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ADB — one button", fontWeight = FontWeight.Bold, color = RPCSXColors.primary, fontFamily = FontFamily.Monospace)
                    SelectableCode("adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS")
                    SelectableCode("adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD --ei d2 64 --ei lx 127")
                    Text("Supported: CROSS/CIRCLE/SQUARE/TRIANGLE/L1/R1/L2/R2/START/SELECT/PS/UP/DOWN/LEFT/RIGHT/L3/R3", fontSize = 11.sp, color = RPCSXColors.textSecondary)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Full sequence — GTA SA EULA→New Game (agent loop)", fontWeight = FontWeight.Bold, color = RPCSXColors.primary, fontFamily = FontFamily.Monospace)
                    SelectableCode("for _ in 1 2 3; do adb shell am broadcast -a com.zenithblue.sambas3.DEBUG_PAD_CROSS; sleep 4; done")
                    Text("Alternative (tap fallback): adb shell input tap 1632 873  # 1920×1080 calibrated", fontSize = 11.sp, color = RPCSXColors.textSecondary)
                    Text("Device $padHint", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = RPCSXColors.textDisabled)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("In-app test (does NOT need adb)", fontWeight = FontWeight.Bold, color = RPCSXColors.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            RPCSX.instance.overlayPadData(0, com.zenithblue.sambas3.Digital2Flags.CELL_PAD_CTRL_CROSS.bit, 127,127,127,127)
                            lastInject = "CROSS press"
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                RPCSX.instance.overlayPadData(0,0,127,127,127,127); lastInject = "CROSS release"
                            },120)
                        }) { Text("X") }
                        Button(onClick = {
                            RPCSX.instance.overlayPadData(com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_UP.bit,0,127,127,127,127)
                            lastInject = "UP press"
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                RPCSX.instance.overlayPadData(0,0,127,127,127,127); lastInject = "UP release"
                            },120)
                        }) { Text("UP") }
                        Button(onClick = { lastInject = "sticks 0,0→255,255 test" ; RPCSX.instance.overlayPadData(0,0,0,0,255,255) }) { Text("Sticks") }
                    }
                    Text("last: $lastInject", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = RPCSXColors.textPrimary)
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Why this exists", fontWeight = FontWeight.Bold, color = RPCSXColors.primary)
                    Text("PadOverlay.kt is a SurfaceView with proportional layout (min(totalW,totalH)/8). Coordinate taps break on rotation/res. The broadcast is rotation-agnostic and works from `scripts/` or `agent-device` loops while the app is in any lifecycle. Log tag DebugPad → BACKEND file.", fontSize = 12.sp, color = RPCSXColors.textSecondary)
                }
            }
            // Live hint: show current config snapshot
            var cfg by remember { mutableStateOf("") }
            LaunchedEffect(Unit) { while(true){ cfg = try{ RPCSX.instance.settingsGet("Video@@Write Color Buffers") } catch(_:Exception){ "" }; delay(2000) } }
        }
    }
}

@Composable
private fun SelectableCode(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = RPCSXColors.primary, modifier = Modifier.background(RPCSXColors.background).padding(6.dp))
}
