package com.zenithblue.sambas3.ui.controller

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayout
import com.zenithblue.sambas3.input.LogicalControl
import com.zenithblue.sambas3.input.LogicalPadState

/**
 * Family-distinct controller visual. Hotspot IDs match repo SVGs under
 * `assets/controllers/`; the Canvas draws a high-contrast silhouette that
 * mirrors those SVG regions (Android has no built-in SVG rasterizer without
 * an extra decoder — Coil alone cannot paint asset SVGs).
 */
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

    val context = LocalContext.current
    val hotspots = remember(family) { ControllerHotspotLayout.hotspotsFor(family) }
    val assetPresent = remember(layout.assetPath) { assetExists(context, layout.assetPath) }
    val accent = when (family) {
        ControllerFamily.PLAYSTATION -> Color(0xFF3AD69B)
        ControllerFamily.XBOX -> Color(0xFF9ACD32)
        ControllerFamily.NINTENDO -> Color(0xFFE60012)
        else -> Color(0xFF8FA3B8)
    }
    val title = when (family) {
        ControllerFamily.PLAYSTATION -> "PLAYSTATION"
        ControllerFamily.XBOX -> "XBOX"
        ControllerFamily.NINTENDO -> "NINTENDO"
        else -> "GENERIC"
    }
    val bodyFill = Color(0xFF1C2533)
    val bodyStroke = accent
    val idleFill = Color(0xFF4A5A6E)
    val activeFill = accent

    Column(modifier.padding(4.dp)) {
        Text(title, color = accent, style = MaterialTheme.typography.labelLarge)
        Text(
            layout.assetPath.substringAfterLast('/') + if (assetPresent) " · asset ok" else " · asset missing",
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(400f / 240f)
                .background(Color(0xFF0B1018), RoundedCornerShape(12.dp))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .pointerInput(family, selected) {
                    detectTapGestures(
                        onTap = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = ControllerHotspotLayout.hitTest(nx, ny, family)
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, family) }
                            if (logical != null) onHotspotClick(logical)
                        },
                        onLongPress = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = ControllerHotspotLayout.hitTest(nx, ny, family)
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, family) }
                            if (logical != null) onHotspotLongPress(logical)
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawFamilyBody(family, w, h, bodyFill, bodyStroke)

                fun lit(id: String, control: LogicalControl?): Boolean {
                    if (pressedHotspots.contains(id)) return true
                    if (control != null && selected == control) return true
                    if (control != null && state.isPressed(control)) return true
                    return false
                }

                for (spot in hotspots) {
                    val control = ControllerHotspotLayout.logicalForHotspot(spot.id, family)
                    val active = lit(spot.id, control)
                    val fill = if (active) activeFill else idleFill
                    val left = spot.left * w
                    val top = spot.top * h
                    val rw = (spot.right - spot.left) * w
                    val rh = (spot.bottom - spot.top) * h
                    when {
                        spot.id.startsWith("stick_") -> {
                            drawCircle(Color(0xFF0E141C), radius = rw / 2f, center = Offset(left + rw / 2f, top + rh / 2f))
                            drawCircle(accent, radius = rw / 2f, center = Offset(left + rw / 2f, top + rh / 2f), style = Stroke(2.dp.toPx()))
                            val stick = if (spot.id == "stick_left") leftStick else rightStick
                            drawCircle(
                                if (active) activeFill else Color(0xFF6B7C90),
                                radius = rw / 3.2f,
                                center = Offset(left + rw / 2f + stick.x * rw / 3f, top + rh / 2f + stick.y * rh / 3f),
                            )
                        }
                        spot.id.startsWith("trigger_") -> {
                            // Drawn via btn_l2/btn_r2 to avoid double paint on overlapping rects.
                        }
                        spot.id == "btn_l2" || spot.id == "btn_r2" -> {
                            val fillAmt = if (spot.id == "btn_l2") leftTrigger else rightTrigger
                            drawRoundRect(Color(0xFF2A3545), Offset(left, top), Size(rw, rh), CornerRadius(4.dp.toPx()))
                            drawRoundRect(accent.copy(alpha = 0.35f), Offset(left, top), Size(rw, rh), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                            val amt = fillAmt.coerceIn(0f, 1f)
                            if (amt > 0.02f || active) {
                                drawRoundRect(
                                    accent.copy(alpha = 0.45f + amt * 0.55f),
                                    Offset(left, top + rh * (1f - amt.coerceAtLeast(0.08f))),
                                    Size(rw, rh * amt.coerceAtLeast(0.08f)),
                                    CornerRadius(4.dp.toPx()),
                                )
                            }
                        }
                        spot.id == "touchpad" -> {
                            drawRoundRect(Color(0xFF243041), Offset(left, top), Size(rw, rh), CornerRadius(6.dp.toPx()))
                            drawRoundRect(accent.copy(alpha = 0.7f), Offset(left, top), Size(rw, rh), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                        spot.id.startsWith("btn_") && (spot.id.contains("dpad") || spot.id == "btn_select" || spot.id == "btn_start" || spot.id == "btn_l1" || spot.id == "btn_r1") -> {
                            drawRoundRect(fill, Offset(left, top), Size(rw, rh), CornerRadius(3.dp.toPx()))
                            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(left, top), Size(rw, rh), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
                        }
                        spot.id == "btn_l3" || spot.id == "btn_r3" -> {
                            // stick nub already conveys L3/R3; keep a small ring when pressed
                            if (active) {
                                drawCircle(activeFill.copy(alpha = 0.55f), radius = minOf(rw, rh) / 2f, center = Offset(left + rw / 2f, top + rh / 2f))
                            }
                        }
                        else -> {
                            val cx = left + rw / 2f
                            val cy = top + rh / 2f
                            val r = minOf(rw, rh) / 2f
                            drawCircle(fill, radius = r, center = Offset(cx, cy))
                            drawCircle(Color.White.copy(alpha = 0.35f), radius = r, center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
                            val label = ControllerHotspotLayout.faceLabel(spot.id, family)
                            if (label != null) {
                                drawFaceLabel(label, cx, cy, r, if (active) Color(0xFF0B1018) else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawFamilyBody(
    family: ControllerFamily,
    w: Float,
    h: Float,
    fill: Color,
    stroke: Color,
) {
    when (family) {
        ControllerFamily.XBOX -> {
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.28f)
                cubicTo(w * 0.12f, h * 0.12f, w * 0.28f, h * 0.10f, w * 0.38f, h * 0.16f)
                lineTo(w * 0.62f, h * 0.16f)
                cubicTo(w * 0.72f, h * 0.10f, w * 0.88f, h * 0.12f, w * 0.88f, h * 0.28f)
                lineTo(w * 0.92f, h * 0.62f)
                cubicTo(w * 0.92f, h * 0.82f, w * 0.72f, h * 0.90f, w * 0.50f, h * 0.90f)
                cubicTo(w * 0.28f, h * 0.90f, w * 0.08f, h * 0.82f, w * 0.08f, h * 0.62f)
                close()
            }
            drawPath(path, fill)
            drawPath(path, stroke, style = Stroke(2.5.dp.toPx()))
        }
        ControllerFamily.NINTENDO -> {
            drawRoundRect(
                fill,
                Offset(w * 0.10f, h * 0.18f),
                Size(w * 0.80f, h * 0.64f),
                CornerRadius(w * 0.07f, w * 0.07f),
            )
            drawRoundRect(
                stroke,
                Offset(w * 0.10f, h * 0.18f),
                Size(w * 0.80f, h * 0.64f),
                CornerRadius(w * 0.07f, w * 0.07f),
                style = Stroke(2.5.dp.toPx()),
            )
            // Joy-con style end caps
            drawCircle(stroke.copy(alpha = 0.35f), radius = h * 0.22f, center = Offset(w * 0.16f, h * 0.50f), style = Stroke(2.dp.toPx()))
            drawCircle(stroke.copy(alpha = 0.35f), radius = h * 0.22f, center = Offset(w * 0.84f, h * 0.50f), style = Stroke(2.dp.toPx()))
        }
        else -> {
            // PlayStation / Generic rounded body
            drawRoundRect(
                fill,
                Offset(w * 0.08f, h * 0.16f),
                Size(w * 0.84f, h * 0.68f),
                CornerRadius(w * 0.10f, w * 0.10f),
            )
            drawRoundRect(
                stroke,
                Offset(w * 0.08f, h * 0.16f),
                Size(w * 0.84f, h * 0.68f),
                CornerRadius(w * 0.10f, w * 0.10f),
                style = Stroke(2.5.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawFaceLabel(label: String, cx: Float, cy: Float, r: Float, color: Color) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = r * 0.95f
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    drawContext.canvas.nativeCanvas.drawText(label, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
}

private fun assetExists(context: Context, assetPath: String): Boolean =
    runCatching { context.assets.open(assetPath).close(); true }.getOrDefault(false)

internal fun LogicalPadState.isPressed(control: LogicalControl): Boolean =
    if (control.bank == 0) digital1 and control.bit != 0 else digital2 and control.bit != 0
