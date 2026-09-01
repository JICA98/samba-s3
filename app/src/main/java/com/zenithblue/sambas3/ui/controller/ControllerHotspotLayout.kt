package com.zenithblue.sambas3.ui.controller

import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl

/** Converts source-derived SVG region IDs into the app's logical controls. */
object ControllerHotspotLayout {
    fun logicalForHotspot(hotspotId: String, family: ControllerFamily): LogicalControl? = when {
        family == ControllerFamily.KEYBOARD -> keyboardLogical(hotspotId)
        else -> when (hotspotId) {
            "btn_dpad_up" -> LogicalControl.DPAD_UP
            "btn_dpad_down" -> LogicalControl.DPAD_DOWN
            "btn_dpad_left" -> LogicalControl.DPAD_LEFT
            "btn_dpad_right" -> LogicalControl.DPAD_RIGHT
            "btn_cross" -> LogicalControl.CROSS
            "btn_circle" -> LogicalControl.CIRCLE
            "btn_square" -> LogicalControl.SQUARE
            "btn_triangle" -> LogicalControl.TRIANGLE
            "btn_l1" -> LogicalControl.L1
            "btn_l2" -> LogicalControl.L2
            "btn_r1" -> LogicalControl.R1
            "btn_r2" -> LogicalControl.R2
            "stick_left" -> LogicalControl.L3
            "stick_right" -> LogicalControl.R3
            "btn_start" -> LogicalControl.START
            "btn_select" -> LogicalControl.SELECT
            "btn_guide" -> LogicalControl.PS_HOME_FRONTEND
            else -> null
        }
    }

    private fun keyboardLogical(code: String): LogicalControl? = when (code) {
        "KeyW", "ArrowUp" -> LogicalControl.DPAD_UP
        "KeyS", "ArrowDown" -> LogicalControl.DPAD_DOWN
        "KeyA", "ArrowLeft" -> LogicalControl.DPAD_LEFT
        "KeyD", "ArrowRight" -> LogicalControl.DPAD_RIGHT
        "KeyJ" -> LogicalControl.CROSS
        "KeyK" -> LogicalControl.CIRCLE
        "KeyU" -> LogicalControl.SQUARE
        "KeyI" -> LogicalControl.TRIANGLE
        "KeyQ" -> LogicalControl.L1
        "KeyE" -> LogicalControl.R1
        "Digit1" -> LogicalControl.L2
        "Digit3" -> LogicalControl.R2
        "ShiftLeft", "ShiftRight" -> LogicalControl.L3
        "Enter" -> LogicalControl.START
        "Tab" -> LogicalControl.SELECT
        "Escape" -> LogicalControl.PS_HOME_FRONTEND
        else -> null
    }

    fun faceLabel(hotspotId: String): String? = when (hotspotId) {
        "btn_cross" -> "✕"
        "btn_circle" -> "○"
        "btn_square" -> "□"
        "btn_triangle" -> "△"
        else -> null
    }

    fun roundTripHotspot(control: LogicalControl, family: ControllerFamily): Boolean {
        val id = ControllerLayoutResolver.hotspotForLogical(control, family)
        return logicalForHotspot(id, family) == control
    }
}
