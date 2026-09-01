package com.zenithblue.sambas3.ui.controller

import kotlin.math.min

data class SvgScreenPoint(
    val x: Float,
    val y: Float,
)

data class SvgScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** The same fit transform is used by the SVG image, highlight layer, and hit testing. */
data class SvgViewportTransform(
    val viewport: SvgViewport,
    val contentWidth: Float,
    val contentHeight: Float,
) {
    val scale: Float = min(contentWidth / viewport.width, contentHeight / viewport.height)
    val offsetX: Float = (contentWidth - viewport.width * scale) / 2f
    val offsetY: Float = (contentHeight - viewport.height * scale) / 2f

    fun sourceToScreen(point: SvgScreenPoint): SvgScreenPoint = SvgScreenPoint(
        x = offsetX + point.x * scale,
        y = offsetY + point.y * scale,
    )

    fun sourceToScreen(bounds: SvgBounds): SvgScreenRect = SvgScreenRect(
        left = offsetX + bounds.left * scale,
        top = offsetY + bounds.top * scale,
        right = offsetX + bounds.right * scale,
        bottom = offsetY + bounds.bottom * scale,
    )

    fun screenToSource(point: SvgScreenPoint): SvgScreenPoint? {
        if (scale <= 0f) return null
        val x = (point.x - offsetX) / scale
        val y = (point.y - offsetY) / scale
        return if (x in 0f..viewport.width && y in 0f..viewport.height) SvgScreenPoint(x, y) else null
    }
}
