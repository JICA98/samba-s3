package com.zenithblue.sambas3.ui.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.LogicalControl

/** Normalized (0–1) hotspot rect inside the visual bounds. */
data class HotspotRect(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(nx: Float, ny: Float): Boolean = nx in left..right && ny in top..bottom
    fun center(): Offset = Offset((left + right) / 2f, (top + bottom) / 2f)
    fun size(): Size = Size((right - left).coerceAtLeast(0.01f), (bottom - top).coerceAtLeast(0.01f))
}

object ControllerHotspotLayout {
    fun gamepadHotspots(): List<HotspotRect> = listOf(
        HotspotRect("btn_l2", 0.12f, 0.02f, 0.28f, 0.10f),
        HotspotRect("btn_r2", 0.72f, 0.02f, 0.88f, 0.10f),
        HotspotRect("btn_l1", 0.12f, 0.10f, 0.28f, 0.18f),
        HotspotRect("btn_r1", 0.72f, 0.10f, 0.88f, 0.18f),
        HotspotRect("btn_dpad_up", 0.18f, 0.32f, 0.28f, 0.42f),
        HotspotRect("btn_dpad_left", 0.10f, 0.42f, 0.20f, 0.52f),
        HotspotRect("btn_dpad_right", 0.26f, 0.42f, 0.36f, 0.52f),
        HotspotRect("btn_dpad_down", 0.18f, 0.52f, 0.28f, 0.62f),
        HotspotRect("btn_triangle", 0.70f, 0.30f, 0.80f, 0.40f),
        HotspotRect("btn_square", 0.62f, 0.40f, 0.72f, 0.50f),
        HotspotRect("btn_circle", 0.78f, 0.40f, 0.88f, 0.50f),
        HotspotRect("btn_cross", 0.70f, 0.50f, 0.80f, 0.60f),
        HotspotRect("btn_y", 0.70f, 0.30f, 0.80f, 0.40f),
        HotspotRect("btn_x", 0.62f, 0.40f, 0.72f, 0.50f),
        HotspotRect("btn_b", 0.78f, 0.40f, 0.88f, 0.50f),
        HotspotRect("btn_a", 0.70f, 0.50f, 0.80f, 0.60f),
        HotspotRect("btn_select", 0.38f, 0.42f, 0.46f, 0.50f),
        HotspotRect("btn_start", 0.54f, 0.42f, 0.62f, 0.50f),
        HotspotRect("btn_guide", 0.46f, 0.30f, 0.54f, 0.40f),
        HotspotRect("stick_left", 0.28f, 0.62f, 0.42f, 0.82f),
        HotspotRect("stick_right", 0.58f, 0.62f, 0.72f, 0.82f),
        HotspotRect("btn_l3", 0.30f, 0.66f, 0.40f, 0.78f),
        HotspotRect("btn_r3", 0.60f, 0.66f, 0.70f, 0.78f),
        HotspotRect("trigger_left", 0.12f, 0.02f, 0.28f, 0.10f),
        HotspotRect("trigger_right", 0.72f, 0.02f, 0.88f, 0.10f),
        HotspotRect("touchpad", 0.35f, 0.18f, 0.65f, 0.30f),
    )

    fun keyboardHotspots(): List<HotspotRect> = listOf(
        HotspotRect("key_esc", 0.04f, 0.08f, 0.12f, 0.18f),
        HotspotRect("key_q", 0.06f, 0.24f, 0.14f, 0.36f),
        HotspotRect("key_w", 0.15f, 0.24f, 0.23f, 0.36f),
        HotspotRect("key_e", 0.24f, 0.24f, 0.32f, 0.36f),
        HotspotRect("key_a", 0.06f, 0.40f, 0.14f, 0.52f),
        HotspotRect("key_s", 0.15f, 0.40f, 0.23f, 0.52f),
        HotspotRect("key_d", 0.24f, 0.40f, 0.32f, 0.52f),
        HotspotRect("key_up", 0.40f, 0.24f, 0.48f, 0.36f),
        HotspotRect("key_left", 0.34f, 0.40f, 0.42f, 0.52f),
        HotspotRect("key_down", 0.40f, 0.40f, 0.48f, 0.52f),
        HotspotRect("key_right", 0.46f, 0.40f, 0.54f, 0.52f),
        HotspotRect("key_u", 0.60f, 0.24f, 0.68f, 0.36f),
        HotspotRect("key_i", 0.68f, 0.24f, 0.76f, 0.36f),
        HotspotRect("key_j", 0.60f, 0.40f, 0.68f, 0.52f),
        HotspotRect("key_k", 0.68f, 0.40f, 0.76f, 0.52f),
        HotspotRect("key_1", 0.82f, 0.24f, 0.90f, 0.36f),
        HotspotRect("key_3", 0.90f, 0.24f, 0.98f, 0.36f),
        HotspotRect("key_2", 0.82f, 0.40f, 0.90f, 0.52f),
        HotspotRect("key_4", 0.90f, 0.40f, 0.98f, 0.52f),
        HotspotRect("key_r", 0.76f, 0.24f, 0.82f, 0.36f),
        HotspotRect("key_f", 0.76f, 0.40f, 0.82f, 0.52f),
        HotspotRect("key_z", 0.06f, 0.58f, 0.14f, 0.70f),
        HotspotRect("key_x", 0.15f, 0.58f, 0.23f, 0.70f),
        HotspotRect("key_c", 0.24f, 0.58f, 0.32f, 0.70f),
        HotspotRect("key_v", 0.32f, 0.58f, 0.40f, 0.70f),
        HotspotRect("key_shift", 0.42f, 0.58f, 0.54f, 0.70f),
        HotspotRect("key_space", 0.54f, 0.58f, 0.68f, 0.70f),
        HotspotRect("key_tab", 0.68f, 0.58f, 0.78f, 0.70f),
        HotspotRect("key_enter", 0.78f, 0.58f, 0.92f, 0.70f),
    )

    fun hotspotsFor(family: ControllerFamily): List<HotspotRect> =
        if (family == ControllerFamily.KEYBOARD) keyboardHotspots() else gamepadHotspots()

    fun logicalForHotspot(hotspotId: String, family: ControllerFamily): LogicalControl? = when (hotspotId) {
        "btn_dpad_up", "key_w" -> LogicalControl.DPAD_UP
        "btn_dpad_down", "key_s" -> LogicalControl.DPAD_DOWN
        "btn_dpad_left", "key_a" -> LogicalControl.DPAD_LEFT
        "btn_dpad_right", "key_d" -> LogicalControl.DPAD_RIGHT
        "btn_cross", "btn_a", "key_j" -> LogicalControl.CROSS
        "btn_circle", "btn_b", "key_k" -> when (family) {
            ControllerFamily.NINTENDO -> LogicalControl.CIRCLE
            else -> LogicalControl.CIRCLE
        }
        "btn_square", "btn_x", "key_u" -> LogicalControl.SQUARE
        "btn_triangle", "btn_y", "key_i" -> LogicalControl.TRIANGLE
        "btn_l1", "key_q" -> LogicalControl.L1
        "btn_r1", "key_e" -> LogicalControl.R1
        "btn_l2", "trigger_left", "key_1" -> LogicalControl.L2
        "btn_r2", "trigger_right", "key_3" -> LogicalControl.R2
        "btn_l3", "stick_left", "key_shift" -> LogicalControl.L3
        "btn_r3", "stick_right" -> LogicalControl.R3
        "btn_start", "key_enter" -> LogicalControl.START
        "btn_select", "key_tab" -> LogicalControl.SELECT
        "btn_guide", "key_esc" -> LogicalControl.PS_HOME_FRONTEND
        else -> null
    }
}
