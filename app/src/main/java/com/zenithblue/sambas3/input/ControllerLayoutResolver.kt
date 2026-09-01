package com.zenithblue.sambas3.input

import android.util.Log

/** Resolved input metadata. Visual artwork is intentionally independent from physical family. */
data class ControllerLayout(
    val family: ControllerFamily,
    val assetPath: String,
    val hotspotIds: Set<String>,
    val physicalLabels: Map<String, String>,
)

object ControllerLayoutResolver {
    private const val TAG = "S3PADUI"

    const val ASSET_DS3 = "controllers/controller_ds3.svg"
    // Kept as source-compatible aliases for callers that only need a family label.
    const val ASSET_PS = ASSET_DS3
    const val ASSET_XBOX = ASSET_DS3
    const val ASSET_SWITCH = ASSET_DS3
    const val ASSET_GENERIC = ASSET_DS3
    const val ASSET_KEYBOARD = "controllers/controller_keyboard.svg"

    /** IDs in controller_ds3_regions.json, matching the source data-button groups. */
    val GAMEPAD_HOTSPOTS: Set<String> = setOf(
        "btn_dpad_up", "btn_dpad_down", "btn_dpad_left", "btn_dpad_right",
        "btn_cross", "btn_circle", "btn_square", "btn_triangle",
        "btn_l1", "btn_l2", "btn_r1", "btn_r2",
        "stick_left", "stick_right", "btn_select", "btn_start", "btn_guide",
    )

    /** Source data-code names for the keyboard controls used by the mapper. */
    val KEYBOARD_HOTSPOTS: Set<String> = setOf(
        "KeyW", "KeyA", "KeyS", "KeyD",
        "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
        "KeyU", "KeyI", "KeyJ", "KeyK",
        "KeyQ", "KeyE", "Digit1", "Digit3",
        "ShiftLeft", "ShiftRight", "Enter", "Tab", "Escape",
    )

    private val PS_LABELS = mapOf(
        "btn_cross" to "Cross", "btn_circle" to "Circle",
        "btn_square" to "Square", "btn_triangle" to "Triangle",
    )
    private val XBOX_LABELS = mapOf(
        "btn_cross" to "A → Cross", "btn_circle" to "B → Circle",
        "btn_square" to "X → Square", "btn_triangle" to "Y → Triangle",
    )
    private val SWITCH_LABELS = mapOf(
        "btn_cross" to "B → Cross", "btn_circle" to "A → Circle",
        "btn_square" to "Y → Square", "btn_triangle" to "X → Triangle",
    )

    fun resolve(family: ControllerFamily): ControllerLayout {
        val physicalLabels = when (family) {
            ControllerFamily.XBOX -> XBOX_LABELS
            ControllerFamily.NINTENDO -> SWITCH_LABELS
            ControllerFamily.KEYBOARD -> emptyMap()
            else -> PS_LABELS
        }
        val layout = if (family == ControllerFamily.KEYBOARD) {
            ControllerLayout(family, ASSET_KEYBOARD, KEYBOARD_HOTSPOTS, emptyMap())
        } else {
            ControllerLayout(family, ASSET_DS3, GAMEPAD_HOTSPOTS, physicalLabels)
        }
        Log.i(TAG, "layout family=$family asset=${layout.assetPath}")
        return layout
    }

    /** Stable logical IDs used by the DS3 source regardless of physical device family. */
    fun hotspotForLogical(control: LogicalControl, family: ControllerFamily): String = if (family == ControllerFamily.KEYBOARD) {
        when (control) {
            LogicalControl.DPAD_UP -> "KeyW"
            LogicalControl.DPAD_DOWN -> "KeyS"
            LogicalControl.DPAD_LEFT -> "KeyA"
            LogicalControl.DPAD_RIGHT -> "KeyD"
            LogicalControl.CROSS -> "KeyJ"
            LogicalControl.CIRCLE -> "KeyK"
            LogicalControl.SQUARE -> "KeyU"
            LogicalControl.TRIANGLE -> "KeyI"
            LogicalControl.L1 -> "KeyQ"
            LogicalControl.R1 -> "KeyE"
            LogicalControl.L2 -> "Digit1"
            LogicalControl.R2 -> "Digit3"
            LogicalControl.L3 -> "ShiftLeft"
            LogicalControl.R3 -> "ShiftRight"
            LogicalControl.START -> "Enter"
            LogicalControl.SELECT -> "Tab"
            LogicalControl.PS_HOME_FRONTEND -> "Escape"
        }
    } else {
        when (control) {
            LogicalControl.DPAD_UP -> "btn_dpad_up"
            LogicalControl.DPAD_DOWN -> "btn_dpad_down"
            LogicalControl.DPAD_LEFT -> "btn_dpad_left"
            LogicalControl.DPAD_RIGHT -> "btn_dpad_right"
            LogicalControl.CROSS -> "btn_cross"
            LogicalControl.CIRCLE -> "btn_circle"
            LogicalControl.SQUARE -> "btn_square"
            LogicalControl.TRIANGLE -> "btn_triangle"
            LogicalControl.L1 -> "btn_l1"
            LogicalControl.L2 -> "btn_l2"
            LogicalControl.R1 -> "btn_r1"
            LogicalControl.R2 -> "btn_r2"
            LogicalControl.L3 -> "stick_left"
            LogicalControl.R3 -> "stick_right"
            LogicalControl.START -> "btn_start"
            LogicalControl.SELECT -> "btn_select"
            LogicalControl.PS_HOME_FRONTEND -> "btn_guide"
        }
    }
}
