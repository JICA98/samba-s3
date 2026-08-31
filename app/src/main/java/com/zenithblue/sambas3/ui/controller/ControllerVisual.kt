package com.zenithblue.sambas3.ui.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayout
import com.zenithblue.sambas3.input.LogicalControl
import com.zenithblue.sambas3.input.LogicalPadState

@Composable
fun ControllerFamilyVisual(
    family: ControllerFamily,
    layout: ControllerLayout,
    state: LogicalPadState,
    pressedHotspots: Set<String>,
    selected: LogicalControl?,
    leftStick: Offset = Offset.Zero,
    rightStick: Offset = Offset.Zero,
    leftTrigger: Float = 0f,
    rightTrigger: Float = 0f,
    onHotspotClick: (LogicalControl) -> Unit,
    onHotspotLongPress: (LogicalControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (family == ControllerFamily.KEYBOARD) {
        KeyboardVisual(
            pressedHotspots = pressedHotspots,
            selected = selected,
            onHotspotClick = onHotspotClick,
            onHotspotLongPress = onHotspotLongPress,
            modifier = modifier,
        )
        return
    }

    val hotspots = remember(family) { ControllerHotspotLayout.gamepadHotspots() }
    val accent = when (family) {
        ControllerFamily.PLAYSTATION -> Color(0xFF3AD69B)
        ControllerFamily.XBOX -> Color(0xFF7CFC00)
        ControllerFamily.NINTENDO -> Color(0xFFE60012)
        else -> Color(0xFF8899AA)
    }
    val title = when (family) {
        ControllerFamily.PLAYSTATION -> "PLAYSTATION"
        ControllerFamily.XBOX -> "XBOX"
        ControllerFamily.NINTENDO -> "NINTENDO"
        else -> "GENERIC"
    }

    Column(modifier.padding(4.dp)) {
        Text(title, color = accent, style = MaterialTheme.typography.labelLarge)
        Text(layout.assetPath.substringAfterLast('/'), color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(400f / 240f)
                .pointerInput(family, selected) {
                    detectTapGestures(
                        onTap = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = hotspots.firstOrNull { it.contains(nx, ny) }
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, family) }
                            if (logical != null) onHotspotClick(logical)
                        },
                        onLongPress = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = hotspots.firstOrNull { it.contains(nx, ny) }
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, family) }
                            if (logical != null) onHotspotLongPress(logical)
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = Color(0xFF1A2332),
                    topLeft = Offset(w * 0.08f, h * 0.16f),
                    size = Size(w * 0.84f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawRoundRect(
                    color = Color(0xFF10151D),
                    topLeft = Offset(w * 0.08f, h * 0.16f),
                    size = Size(w * 0.84f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                )

                fun lit(id: String, control: LogicalControl?): Boolean {
                    if (pressedHotspots.contains(id)) return true
                    if (control != null && selected == control) return true
                    if (control != null && state.isPressed(control)) return true
                    return false
                }

                for (spot in hotspots.distinctBy { it.id }) {
                    val control = ControllerHotspotLayout.logicalForHotspot(spot.id, family)
                    val active = lit(spot.id, control)
                    val fill = when {
                        active -> accent.copy(alpha = 0.75f)
                        else -> Color(0xFF2B3746)
                    }
                    val left = spot.left * w
                    val top = spot.top * h
                    val rw = (spot.right - spot.left) * w
                    val rh = (spot.bottom - spot.top) * h
                    when {
                        spot.id.startsWith("stick_") -> {
                            drawCircle(Color(0xFF151C27), radius = rw / 2f, center = Offset(left + rw / 2f, top + rh / 2f))
                            drawCircle(accent.copy(alpha = 0.5f), radius = rw / 2f, center = Offset(left + rw / 2f, top + rh / 2f), style = Stroke(1.dp.toPx()))
                            val stick = if (spot.id == "stick_left") leftStick else rightStick
                            drawCircle(
                                fill,
                                radius = rw / 4f,
                                center = Offset(left + rw / 2f + stick.x * rw / 3f, top + rh / 2f + stick.y * rh / 3f),
                            )
                        }
                        spot.id.startsWith("trigger_") || spot.id == "btn_l2" || spot.id == "btn_r2" -> {
                            val fillAmt = if (spot.id.contains("left") || spot.id == "btn_l2") leftTrigger else rightTrigger
                            drawRoundRect(Color(0xFF243041), Offset(left, top), Size(rw, rh), CornerRadius(4.dp.toPx()))
                            if (fillAmt > 0.05f || active) {
                                drawRoundRect(accent.copy(alpha = 0.35f + fillAmt * 0.65f), Offset(left, top + rh * (1f - fillAmt.coerceIn(0f, 1f))), Size(rw, rh * fillAmt.coerceIn(0.05f, 1f)), CornerRadius(4.dp.toPx()))
                            }
                        }
                        spot.id.startsWith("btn_") && (spot.id.contains("dpad") || spot.id == "btn_select" || spot.id == "btn_start" || spot.id == "btn_l1" || spot.id == "btn_r1") -> {
                            drawRoundRect(fill, Offset(left, top), Size(rw, rh), CornerRadius(3.dp.toPx()))
                        }
                        else -> {
                            drawCircle(fill, radius = minOf(rw, rh) / 2f, center = Offset(left + rw / 2f, top + rh / 2f))
                        }
                    }
                }
            }
        }
    }
}

internal fun LogicalPadState.isPressed(control: LogicalControl): Boolean =
    if (control.bank == 0) digital1 and control.bit != 0 else digital2 and control.bit != 0
