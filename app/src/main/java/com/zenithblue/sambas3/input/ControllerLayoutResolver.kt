package com.zenithblue.sambas3.input

import android.util.Log

/**
 * Resolves family → asset path + hotspot IDs for the Controls visual.
 * Assets live under assets/controllers/ as repo-local SVGs.
 */
data class ControllerLayout(
    val family: ControllerFamily,
    val assetPath: String,
    val hotspotIds: Set<String>,
    val physicalLabels: Map<String, String>,
)

object ControllerLayoutResolver {
    private const val TAG = "S3PADUI"

    const val ASSET_PS = "controllers/controller_ps.svg"
    const val ASSET_XBOX = "controllers/controller_xbox.svg"
    const val ASSET_SWITCH = "controllers/controller_switch.svg"
    const val ASSET_GENERIC = "controllers/controller_generic.svg"
    const val ASSET_KEYBOARD = "controllers/controller_keyboard.svg"

    /** Hotspot IDs required on every gamepad family SVG. */
    val GAMEPAD_HOTSPOTS: Set<String> = setOf(
        "btn_dpad_up", "btn_dpad_down", "btn_dpad_left", "btn_dpad_right",
        "btn_cross", "btn_circle", "btn_square", "btn_triangle",
        "btn_a", "btn_b", "btn_x", "btn_y",
        "btn_l1", "btn_l2", "btn_r1", "btn_r2", "btn_l3", "btn_r3",
        "btn_select", "btn_start", "btn_guide",
        "stick_left", "stick_right", "trigger_left", "trigger_right",
        "touchpad",
    )

    /** Keyboard-specific hotspot IDs (never a gamepad silhouette). */
    val KEYBOARD_HOTSPOTS: Set<String> = setOf(
        "key_w", "key_a", "key_s", "key_d",
        "key_up", "key_down", "key_left", "key_right",
        "key_z", "key_x", "key_c", "key_v",
        "key_q", "key_e", "key_r", "key_f",
        "key_enter", "key_space", "key_shift", "key_tab", "key_esc",
        "key_1", "key_2", "key_3", "key_4",
    )

    private val PS_LABELS = mapOf(
        "btn_cross" to "Cross", "btn_circle" to "Circle",
        "btn_square" to "Square", "btn_triangle" to "Triangle",
        "btn_a" to "Cross", "btn_b" to "Circle", "btn_x" to "Square", "btn_y" to "Triangle",
    )
    private val XBOX_LABELS = mapOf(
        "btn_a" to "A", "btn_b" to "B", "btn_x" to "X", "btn_y" to "Y",
        "btn_cross" to "A → Cross", "btn_circle" to "B → Circle",
        "btn_square" to "X → Square", "btn_triangle" to "Y → Triangle",
    )
    private val SWITCH_LABELS = mapOf(
        "btn_a" to "A", "btn_b" to "B", "btn_x" to "X", "btn_y" to "Y",
        "btn_cross" to "B → Cross", "btn_circle" to "A → Circle",
        "btn_square" to "Y → Square", "btn_triangle" to "X → Triangle",
    )

    fun resolve(family: ControllerFamily): ControllerLayout {
        val layout = when (family) {
            ControllerFamily.PLAYSTATION -> ControllerLayout(family, ASSET_PS, GAMEPAD_HOTSPOTS, PS_LABELS)
            ControllerFamily.XBOX -> ControllerLayout(family, ASSET_XBOX, GAMEPAD_HOTSPOTS, XBOX_LABELS)
            ControllerFamily.NINTENDO -> ControllerLayout(family, ASSET_SWITCH, GAMEPAD_HOTSPOTS, SWITCH_LABELS)
            ControllerFamily.KEYBOARD -> ControllerLayout(family, ASSET_KEYBOARD, KEYBOARD_HOTSPOTS, emptyMap())
            ControllerFamily.TOUCH_CONTROLLER,
            ControllerFamily.GENERIC_GAMEPAD,
            ControllerFamily.UNKNOWN -> ControllerLayout(family, ASSET_GENERIC, GAMEPAD_HOTSPOTS, PS_LABELS)
        }
        Log.i(TAG, "layout family=$family asset=${layout.assetPath}")
        return layout
    }

    /**
     * Primary interactive hotspot id for a logical control on the given family layout.
     * Xbox: A/B/X/Y. Nintendo: B/A/Y/X for Cross/Circle/Square/Triangle (no id collisions).
     */
    fun hotspotForLogical(control: LogicalControl, family: ControllerFamily): String = when (control) {
        LogicalControl.DPAD_UP -> "btn_dpad_up"
        LogicalControl.DPAD_DOWN -> "btn_dpad_down"
        LogicalControl.DPAD_LEFT -> "btn_dpad_left"
        LogicalControl.DPAD_RIGHT -> "btn_dpad_right"
        LogicalControl.CROSS -> when (family) {
            ControllerFamily.XBOX -> "btn_a"
            ControllerFamily.NINTENDO -> "btn_b"
            else -> "btn_cross"
        }
        LogicalControl.CIRCLE -> when (family) {
            ControllerFamily.XBOX -> "btn_b"
            ControllerFamily.NINTENDO -> "btn_a"
            else -> "btn_circle"
        }
        LogicalControl.SQUARE -> when (family) {
            ControllerFamily.XBOX -> "btn_x"
            ControllerFamily.NINTENDO -> "btn_y"
            else -> "btn_square"
        }
        LogicalControl.TRIANGLE -> when (family) {
            ControllerFamily.XBOX -> "btn_y"
            ControllerFamily.NINTENDO -> "btn_x"
            else -> "btn_triangle"
        }
        LogicalControl.L1 -> "btn_l1"
        LogicalControl.R1 -> "btn_r1"
        LogicalControl.L2 -> "btn_l2"
        LogicalControl.R2 -> "btn_r2"
        LogicalControl.L3 -> "btn_l3"
        LogicalControl.R3 -> "btn_r3"
        LogicalControl.START -> "btn_start"
        LogicalControl.SELECT -> "btn_select"
        LogicalControl.PS_HOME_FRONTEND -> "btn_guide"
    }
}
