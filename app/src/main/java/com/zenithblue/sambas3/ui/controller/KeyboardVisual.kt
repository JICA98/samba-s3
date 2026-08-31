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
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl

@Composable
fun KeyboardVisual(
    pressedHotspots: Set<String>,
    selected: LogicalControl?,
    onHotspotClick: (LogicalControl) -> Unit,
    onHotspotLongPress: (LogicalControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hotspots = remember { ControllerHotspotLayout.keyboardHotspots() }
    val accent = Color(0xFF3AD69B)

    Column(modifier.padding(4.dp)) {
        Text("KEYBOARD", color = accent, style = MaterialTheme.typography.labelLarge)
        Text(ControllerLayoutResolver.ASSET_KEYBOARD.substringAfterLast('/'), color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(480f / 220f)
                .pointerInput(selected) {
                    detectTapGestures(
                        onTap = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = hotspots.firstOrNull { it.contains(nx, ny) }
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, ControllerFamily.KEYBOARD) }
                            if (logical != null) onHotspotClick(logical)
                        },
                        onLongPress = { offset ->
                            val nx = offset.x / size.width
                            val ny = offset.y / size.height
                            val hit = hotspots.firstOrNull { it.contains(nx, ny) }
                            val logical = hit?.let { ControllerHotspotLayout.logicalForHotspot(it.id, ControllerFamily.KEYBOARD) }
                            if (logical != null) onHotspotLongPress(logical)
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawRoundRect(
                    color = Color(0xFF121820),
                    topLeft = Offset(w * 0.02f, h * 0.05f),
                    size = Size(w * 0.96f, h * 0.9f),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                drawRoundRect(
                    color = accent.copy(alpha = 0.5f),
                    topLeft = Offset(w * 0.02f, h * 0.05f),
                    size = Size(w * 0.96f, h * 0.9f),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
                for (spot in hotspots) {
                    val control = ControllerHotspotLayout.logicalForHotspot(spot.id, ControllerFamily.KEYBOARD)
                    val active = pressedHotspots.contains(spot.id) || selected == control
                    val fill = if (active) accent.copy(alpha = 0.8f) else Color(0xFF2B3746)
                    drawRoundRect(
                        color = fill,
                        topLeft = Offset(spot.left * w, spot.top * h),
                        size = Size((spot.right - spot.left) * w, (spot.bottom - spot.top) * h),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }
    }
}
