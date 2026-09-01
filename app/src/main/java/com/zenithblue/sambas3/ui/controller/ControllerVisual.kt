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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayout
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl
import com.zenithblue.sambas3.input.LogicalPadState

private const val TAG = "S3SVG"
private const val DS3_ASPECT_RATIO = 1000f / 500f

/** Renders the supplied DS3 artwork with a transparent Compose interaction layer. */
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
    val imageLoader = rememberSvgImageLoader(context)
    val regionMap = remember(context) {
        runCatching { SvgRegionRegistry.loadController(context) }
            .onFailure { Log.e(TAG, "Unable to load DS3 interaction regions", it) }
            .getOrNull()
    }
    if (regionMap == null) {
        // Never hide a packaging/decoder failure behind crude replacement art.
        Box(modifier.padding(4.dp))
        return
    }

    val selectedId = selected?.let { ControllerLayoutResolver.hotspotForLogical(it, family) }
    Box(
        modifier
            .padding(4.dp)
            .fillMaxWidth()
            .aspectRatio(DS3_ASPECT_RATIO)
            .pointerInput(family, selected, regionMap) {
                detectTapGestures(
                    onTap = { offset ->
                        regionMap.logicalAt(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), family)?.let(onHotspotClick)
                    },
                    onLongPress = { offset ->
                        regionMap.logicalAt(offset.x, offset.y, size.width.toFloat(), size.height.toFloat(), family)?.let(onHotspotLongPress)
                    },
                )
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("file:///android_asset/${layout.assetPath}")
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val transform = SvgViewportTransform(regionMap.viewBox, size.width, size.height)
            for (region in regionMap.regions) {
                val logical = ControllerHotspotLayout.logicalForHotspot(region.id, family)
                val active = pressedHotspots.contains(region.id) ||
                    selectedId == region.id ||
                    (logical != null && state.isPressed(logical))
                val stick = when (region.id) {
                    "stick_left" -> leftStick
                    "stick_right" -> rightStick
                    else -> Offset.Zero
                }
                val analogActive = region.kind == "stick" && stick.getDistance() > 0.035f
                if (active || analogActive) {
                    drawSvgPressHighlight(
                        region = region,
                        transform = transform,
                        accent = Color(0xFF59D5FF),
                        stick = stick,
                        triggerAmount = when (region.id) {
                            "btn_l2" -> leftTrigger
                            "btn_r2" -> rightTrigger
                            else -> 0f
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun rememberSvgImageLoader(context: android.content.Context): ImageLoader =
    remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

private fun ControllerSvgRegionMap.logicalAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    family: ControllerFamily,
): LogicalControl? {
    val source = SvgViewportTransform(viewBox, width, height).screenToSource(SvgScreenPoint(x, y)) ?: return null
    // Later SVG groups are painted above earlier groups (notably R1/L1 over
    // the trigger artwork), so reverse document order for overlapping bounds.
    return regions.asReversed()
        .firstOrNull { it.bounds.contains(source.x, source.y) }
        ?.let { ControllerHotspotLayout.logicalForHotspot(it.id, family) }
}

private fun DrawScope.drawSvgPressHighlight(
    region: ControllerSvgRegion,
    transform: SvgViewportTransform,
    accent: Color,
    stick: Offset,
    triggerAmount: Float,
) {
    val rect = transform.sourceToScreen(region.bounds)
    val topLeft = Offset(rect.left, rect.top)
    val rectSize = Size(rect.right - rect.left, rect.bottom - rect.top)
    if (region.kind == "stick") {
        val center = Offset((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
        val radius = minOf(rectSize.width, rectSize.height) / 2f
        drawCircle(accent.copy(alpha = 0.18f), radius = radius, center = center)
        drawCircle(accent, radius = radius, center = center, style = Stroke(2.dp.toPx()))
        val magnitude = stick.getDistance().coerceIn(0f, 1f)
        drawCircle(
            Color.White.copy(alpha = 0.9f),
            radius = 4.dp.toPx() + magnitude * 2.dp.toPx(),
            center = center + Offset(stick.x * radius * 0.26f, stick.y * radius * 0.26f),
        )
        return
    }
    drawRoundRect(
        accent.copy(alpha = 0.16f + triggerAmount.coerceIn(0f, 1f) * 0.12f),
        topLeft,
        rectSize,
        cornerRadius = CornerRadius(5.dp.toPx()),
    )
    drawRoundRect(
        accent,
        topLeft,
        rectSize,
        cornerRadius = CornerRadius(5.dp.toPx()),
        style = Stroke(2.dp.toPx()),
    )
}

internal fun LogicalPadState.isPressed(control: LogicalControl): Boolean =
    if (control.bank == 0) digital1 and control.bit != 0 else digital2 and control.bit != 0
