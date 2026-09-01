package com.zenithblue.sambas3.ui.controller

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl

private const val TAG = "S3SVG"

@Composable
fun KeyboardVisual(
    pressedHotspots: Set<String>,
    selected: LogicalControl?,
    onHotspotClick: (LogicalControl) -> Unit,
    onHotspotLongPress: (LogicalControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageLoader = rememberSvgImageLoader(context)
    val regionMap = remember(context) {
        runCatching { SvgRegionRegistry.loadKeyboard(context) }
            .onFailure { Log.e(TAG, "Unable to load keyboard interaction regions", it) }
            .getOrNull()
    }
    if (regionMap == null) {
        Box(modifier.padding(4.dp))
        return
    }

    val selectedCode = selected?.let { ControllerLayoutResolver.hotspotForLogical(it, ControllerFamily.KEYBOARD) }
    Box(
        modifier
            .padding(4.dp)
            .fillMaxWidth()
            .aspectRatio(regionMap.viewBox.width / regionMap.viewBox.height)
            .pointerInput(selected, regionMap) {
                detectTapGestures(
                    onTap = { offset ->
                        regionMap.logicalAt(offset.x, offset.y, size.width, size.height)?.let(onHotspotClick)
                    },
                    onLongPress = { offset ->
                        regionMap.logicalAt(offset.x, offset.y, size.width, size.height)?.let(onHotspotLongPress)
                    },
                )
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/controllers/controller_keyboard.svg")
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val transform = SvgViewportTransform(regionMap.viewBox, size.width, size.height)
            for (region in regionMap.regions) {
                if (pressedHotspots.contains(region.code) || selectedCode == region.code) {
                    val rect = transform.sourceToScreen(region.bounds)
                    val topLeft = Offset(rect.left, rect.top)
                    val rectSize = Size(rect.right - rect.left, rect.bottom - rect.top)
                    drawRoundRect(Color(0xFF59D5FF).copy(alpha = 0.18f), topLeft, rectSize, CornerRadius(5.dp.toPx()))
                    drawRoundRect(Color(0xFF9FEAFF), topLeft, rectSize, CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

private fun KeyboardSvgRegionMap.logicalAt(x: Float, y: Float, width: Int, height: Int): LogicalControl? {
    val source = SvgViewportTransform(viewBox, width.toFloat(), height.toFloat()).screenToSource(SvgScreenPoint(x, y)) ?: return null
    return regions.firstOrNull { it.bounds.contains(source.x, source.y) }
        ?.let { ControllerHotspotLayout.logicalForHotspot(it.code, ControllerFamily.KEYBOARD) }
}

private fun KeyboardSvgRegionMap.logicalAt(x: Float, y: Float, width: Float, height: Float): LogicalControl? {
    val source = SvgViewportTransform(viewBox, width, height).screenToSource(SvgScreenPoint(x, y)) ?: return null
    return regions.firstOrNull { it.bounds.contains(source.x, source.y) }
        ?.let { ControllerHotspotLayout.logicalForHotspot(it.code, ControllerFamily.KEYBOARD) }
}
