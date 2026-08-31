package com.zenithblue.sambas3.ui.controller

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl

/** Normalized (0–1) hotspot rect inside the visual bounds. */
data class HotspotRect(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(nx: Float, ny: Float): Boolean = nx in left..right && ny in top..bottom
    fun center(): Offset = Offset((left + right) / 2f, (top + bottom) / 2f)
    fun size(): Size = Size((right - left).coerceAtLeast(0.01f), (bottom - top).coerceAtLeast(0.01f))
}

object ControllerHotspotLayout {
    /** Shared shoulders / dpad / meta / sticks (family-agnostic positions). */
    private fun sharedHotspots(includeTouchpad: Boolean): List<HotspotRect> = buildList {
        add(HotspotRect("btn_l2", 0.12f, 0.02f, 0.28f, 0.10f))
        add(HotspotRect("btn_r2", 0.72f, 0.02f, 0.88f, 0.10f))
        add(HotspotRect("btn_l1", 0.12f, 0.10f, 0.28f, 0.18f))
        add(HotspotRect("btn_r1", 0.72f, 0.10f, 0.88f, 0.18f))
        add(HotspotRect("trigger_left", 0.12f, 0.02f, 0.28f, 0.10f))
        add(HotspotRect("trigger_right", 0.72f, 0.02f, 0.88f, 0.10f))
        add(HotspotRect("btn_dpad_up", 0.18f, 0.32f, 0.28f, 0.42f))
        add(HotspotRect("btn_dpad_left", 0.10f, 0.42f, 0.20f, 0.52f))
        add(HotspotRect("btn_dpad_right", 0.26f, 0.42f, 0.36f, 0.52f))
        add(HotspotRect("btn_dpad_down", 0.18f, 0.52f, 0.28f, 0.62f))
        add(HotspotRect("btn_select", 0.38f, 0.42f, 0.46f, 0.50f))
        add(HotspotRect("btn_start", 0.54f, 0.42f, 0.62f, 0.50f))
        add(HotspotRect("btn_guide", 0.46f, 0.30f, 0.54f, 0.40f))
        add(HotspotRect("stick_left", 0.28f, 0.62f, 0.42f, 0.82f))
        add(HotspotRect("stick_right", 0.58f, 0.62f, 0.72f, 0.82f))
        add(HotspotRect("btn_l3", 0.30f, 0.66f, 0.40f, 0.78f))
        add(HotspotRect("btn_r3", 0.60f, 0.66f, 0.70f, 0.78f))
        if (includeTouchpad) add(HotspotRect("touchpad", 0.35f, 0.18f, 0.65f, 0.30f))
    }

    /** PlayStation / Generic: face cluster uses PS symbol IDs only (no overlapping ABXY rects). */
    fun playstationHotspots(): List<HotspotRect> = sharedHotspots(includeTouchpad = true) + listOf(
        HotspotRect("btn_triangle", 0.70f, 0.30f, 0.80f, 0.40f),
        HotspotRect("btn_square", 0.62f, 0.40f, 0.72f, 0.50f),
        HotspotRect("btn_circle", 0.78f, 0.40f, 0.88f, 0.50f),
        HotspotRect("btn_cross", 0.70f, 0.50f, 0.80f, 0.60f),
    )

    /** Xbox / Nintendo: face cluster uses A/B/X/Y IDs only. */
    fun abxyHotspots(): List<HotspotRect> = sharedHotspots(includeTouchpad = false) + listOf(
        HotspotRect("btn_y", 0.70f, 0.30f, 0.80f, 0.40f),
        HotspotRect("btn_x", 0.62f, 0.40f, 0.72f, 0.50f),
        HotspotRect("btn_b", 0.78f, 0.40f, 0.88f, 0.50f),
        HotspotRect("btn_a", 0.70f, 0.50f, 0.80f, 0.60f),
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

    fun hotspotsFor(family: ControllerFamily): List<HotspotRect> = when (family) {
        ControllerFamily.KEYBOARD -> keyboardHotspots()
        ControllerFamily.XBOX, ControllerFamily.NINTENDO -> abxyHotspots()
        ControllerFamily.PLAYSTATION,
        ControllerFamily.GENERIC_GAMEPAD,
        ControllerFamily.TOUCH_CONTROLLER,
        ControllerFamily.UNKNOWN -> playstationHotspots()
    }

    /** @deprecated Prefer [hotspotsFor]; kept for call-site migration. */
    fun gamepadHotspots(): List<HotspotRect> = playstationHotspots()

    /**
     * Hit-test using family-specific hotspot list so overlapping PS/ABXY aliases cannot steal taps.
     * Prefer the hotspot whose id matches [ControllerLayoutResolver.hotspotForLogical] for that family.
     */
    fun hitTest(nx: Float, ny: Float, family: ControllerFamily): HotspotRect? {
        val spots = hotspotsFor(family)
        val hits = spots.filter { it.contains(nx, ny) }
        if (hits.isEmpty()) return null
        if (hits.size == 1) return hits.first()
        // Prefer non-trigger alias when both trigger_* and btn_l2/r2 overlap.
        return hits.firstOrNull { !it.id.startsWith("trigger_") } ?: hits.first()
    }

    fun logicalForHotspot(hotspotId: String, family: ControllerFamily): LogicalControl? = when (hotspotId) {
        "btn_dpad_up", "key_w" -> LogicalControl.DPAD_UP
        "btn_dpad_down", "key_s" -> LogicalControl.DPAD_DOWN
        "btn_dpad_left", "key_a" -> LogicalControl.DPAD_LEFT
        "btn_dpad_right", "key_d" -> LogicalControl.DPAD_RIGHT
        "btn_cross", "key_j" -> LogicalControl.CROSS
        "btn_circle", "key_k" -> LogicalControl.CIRCLE
        "btn_square", "key_u" -> LogicalControl.SQUARE
        "btn_triangle", "key_i" -> LogicalControl.TRIANGLE
        // Xbox: A/B/X/Y → Cross/Circle/Square/Triangle. Nintendo: B/A/Y/X → Cross/Circle/Square/Triangle.
        "btn_a" -> when (family) {
            ControllerFamily.NINTENDO -> LogicalControl.CIRCLE
            else -> LogicalControl.CROSS
        }
        "btn_b" -> when (family) {
            ControllerFamily.NINTENDO -> LogicalControl.CROSS
            else -> LogicalControl.CIRCLE
        }
        "btn_x" -> when (family) {
            ControllerFamily.NINTENDO -> LogicalControl.TRIANGLE
            else -> LogicalControl.SQUARE
        }
        "btn_y" -> when (family) {
            ControllerFamily.NINTENDO -> LogicalControl.SQUARE
            else -> LogicalControl.TRIANGLE
        }
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

    /** Face-button label drawn on the visual for the given hotspot id. */
    fun faceLabel(hotspotId: String, family: ControllerFamily): String? = when (family) {
        ControllerFamily.PLAYSTATION, ControllerFamily.GENERIC_GAMEPAD, ControllerFamily.UNKNOWN, ControllerFamily.TOUCH_CONTROLLER -> when (hotspotId) {
            "btn_cross" -> "✕"
            "btn_circle" -> "○"
            "btn_square" -> "□"
            "btn_triangle" -> "△"
            else -> null
        }
        ControllerFamily.XBOX -> when (hotspotId) {
            "btn_a" -> "A"
            "btn_b" -> "B"
            "btn_x" -> "X"
            "btn_y" -> "Y"
            else -> null
        }
        ControllerFamily.NINTENDO -> when (hotspotId) {
            "btn_a" -> "A"
            "btn_b" -> "B"
            "btn_x" -> "X"
            "btn_y" -> "Y"
            else -> null
        }
        ControllerFamily.KEYBOARD -> null
    }

    /** Round-trip sanity: resolver hotspot → logical → resolver hotspot. */
    fun roundTripHotspot(control: LogicalControl, family: ControllerFamily): Boolean {
        val id = ControllerLayoutResolver.hotspotForLogical(control, family)
        val back = logicalForHotspot(id, family)
        return back == control
    }
}
