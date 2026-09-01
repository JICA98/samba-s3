package com.zenithblue.sambas3.input

import android.view.KeyEvent

/** Friendly labels for the normal Controls UI; Android constants stay in diagnostics. */
object PhysicalInputLabelFormatter {
    fun key(keyCode: Int?): String = keyCode?.let { code ->
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_BUTTON_A -> "A"
            KeyEvent.KEYCODE_BUTTON_B -> "B"
            KeyEvent.KEYCODE_BUTTON_X -> "X"
            KeyEvent.KEYCODE_BUTTON_Y -> "Y"
            KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
            KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
            KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
            KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
            KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
            KeyEvent.KEYCODE_BUTTON_START -> "Start"
            KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
            KeyEvent.KEYCODE_BUTTON_MODE -> "Guide"
            KeyEvent.KEYCODE_HOME -> "Home"
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Left Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "Right Shift"
            KeyEvent.KEYCODE_NUMPAD_0 -> "Num 0"
            KeyEvent.KEYCODE_NUMPAD_1 -> "Num 1"
            KeyEvent.KEYCODE_NUMPAD_2 -> "Num 2"
            KeyEvent.KEYCODE_NUMPAD_3 -> "Num 3"
            KeyEvent.KEYCODE_NUMPAD_4 -> "Num 4"
            KeyEvent.KEYCODE_NUMPAD_5 -> "Num 5"
            KeyEvent.KEYCODE_NUMPAD_6 -> "Num 6"
            KeyEvent.KEYCODE_NUMPAD_7 -> "Num 7"
            KeyEvent.KEYCODE_NUMPAD_8 -> "Num 8"
            KeyEvent.KEYCODE_NUMPAD_9 -> "Num 9"
            else -> KeyEvent.keyCodeToString(code)
                .removePrefix("KEYCODE_")
                .replace('_', ' ')
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        }
    } ?: "Unassigned"
}

/** Maps physical Android keyboard codes to the key IDs used by the keyboard visual. */
object KeyboardKeyVisualRegistry {
    fun hotspotForKey(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_W -> "key_w"
        KeyEvent.KEYCODE_A -> "key_a"
        KeyEvent.KEYCODE_S -> "key_s"
        KeyEvent.KEYCODE_D -> "key_d"
        KeyEvent.KEYCODE_DPAD_UP -> "key_up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "key_down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "key_left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "key_right"
        KeyEvent.KEYCODE_U -> "key_u"
        KeyEvent.KEYCODE_I -> "key_i"
        KeyEvent.KEYCODE_J -> "key_j"
        KeyEvent.KEYCODE_K -> "key_k"
        KeyEvent.KEYCODE_Z -> "key_z"
        KeyEvent.KEYCODE_X -> "key_x"
        KeyEvent.KEYCODE_C -> "key_c"
        KeyEvent.KEYCODE_V -> "key_v"
        KeyEvent.KEYCODE_Q -> "key_q"
        KeyEvent.KEYCODE_E -> "key_e"
        KeyEvent.KEYCODE_R -> "key_r"
        KeyEvent.KEYCODE_F -> "key_f"
        KeyEvent.KEYCODE_1 -> "key_1"
        KeyEvent.KEYCODE_2 -> "key_2"
        KeyEvent.KEYCODE_3 -> "key_3"
        KeyEvent.KEYCODE_4 -> "key_4"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "key_shift"
        KeyEvent.KEYCODE_SPACE -> "key_space"
        KeyEvent.KEYCODE_TAB -> "key_tab"
        KeyEvent.KEYCODE_ENTER -> "key_enter"
        KeyEvent.KEYCODE_ESCAPE -> "key_esc"
        else -> null
    }
}
