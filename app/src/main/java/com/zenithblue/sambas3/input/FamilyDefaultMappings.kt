package com.zenithblue.sambas3.input

import android.view.KeyEvent
import com.zenithblue.sambas3.utils.InputBindingPrefs

/**
 * Family-aware default digital bindings. Merges into existing InputBindingPrefs /
 * ControllerProfile truth — does not invent a parallel map.
 */
object FamilyDefaultMappings {

    /** Xbox-style A/B/X/Y → PS logical (same as Android / InputBindingPrefs defaults). */
    fun gamepadDefaults(): Map<LogicalControl, Int> = mapOf(
        LogicalControl.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
        LogicalControl.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        LogicalControl.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        LogicalControl.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_A,
        LogicalControl.CIRCLE to KeyEvent.KEYCODE_BUTTON_B,
        LogicalControl.SQUARE to KeyEvent.KEYCODE_BUTTON_X,
        LogicalControl.TRIANGLE to KeyEvent.KEYCODE_BUTTON_Y,
        LogicalControl.L1 to KeyEvent.KEYCODE_BUTTON_L1,
        LogicalControl.R1 to KeyEvent.KEYCODE_BUTTON_R1,
        LogicalControl.L2 to KeyEvent.KEYCODE_BUTTON_L2,
        LogicalControl.R2 to KeyEvent.KEYCODE_BUTTON_R2,
        LogicalControl.L3 to KeyEvent.KEYCODE_BUTTON_THUMBL,
        LogicalControl.R3 to KeyEvent.KEYCODE_BUTTON_THUMBR,
        LogicalControl.START to KeyEvent.KEYCODE_BUTTON_START,
        LogicalControl.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        LogicalControl.PS_HOME_FRONTEND to KeyEvent.KEYCODE_BUTTON_MODE,
    )

    /**
     * Nintendo often swaps A/B and X/Y physically relative to Xbox.
     * Logical PS actions still map through Android keycodes the OS reports.
     */
    fun nintendoDefaults(): Map<LogicalControl, Int> = gamepadDefaults()

    fun playstationDefaults(): Map<LogicalControl, Int> = gamepadDefaults()

    fun keyboardDefaults(): Map<LogicalControl, Int> = mapOf(
        // PC Gamepad uses WASD for the left analog stick. Numpad keeps a useful
        // secondary D-pad without stealing the movement keys.
        LogicalControl.DPAD_UP to KeyEvent.KEYCODE_NUMPAD_8,
        LogicalControl.DPAD_DOWN to KeyEvent.KEYCODE_NUMPAD_2,
        LogicalControl.DPAD_LEFT to KeyEvent.KEYCODE_NUMPAD_4,
        LogicalControl.DPAD_RIGHT to KeyEvent.KEYCODE_NUMPAD_6,
        LogicalControl.CROSS to KeyEvent.KEYCODE_J,
        LogicalControl.CIRCLE to KeyEvent.KEYCODE_K,
        LogicalControl.SQUARE to KeyEvent.KEYCODE_U,
        LogicalControl.TRIANGLE to KeyEvent.KEYCODE_I,
        LogicalControl.L1 to KeyEvent.KEYCODE_Q,
        LogicalControl.R1 to KeyEvent.KEYCODE_E,
        LogicalControl.L2 to KeyEvent.KEYCODE_1,
        LogicalControl.R2 to KeyEvent.KEYCODE_3,
        LogicalControl.L3 to KeyEvent.KEYCODE_SHIFT_LEFT,
        LogicalControl.R3 to KeyEvent.KEYCODE_SHIFT_RIGHT,
        LogicalControl.START to KeyEvent.KEYCODE_ENTER,
        LogicalControl.SELECT to KeyEvent.KEYCODE_TAB,
        LogicalControl.PS_HOME_FRONTEND to KeyEvent.KEYCODE_ESCAPE,
    )

    fun keyboardAnalogDefaults(): KeyboardAnalogBindings = KeyboardAnalogBindings(
        leftX = DigitalAxisPair(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D),
        leftY = DigitalAxisPair(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S),
        rightX = DigitalAxisPair(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT),
        rightY = DigitalAxisPair(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN),
    )

    fun keyboardDpadDefaults(): Map<LogicalControl, Int> = keyboardDefaults().toMutableMap().apply {
        put(LogicalControl.DPAD_UP, KeyEvent.KEYCODE_W)
        put(LogicalControl.DPAD_DOWN, KeyEvent.KEYCODE_S)
        put(LogicalControl.DPAD_LEFT, KeyEvent.KEYCODE_A)
        put(LogicalControl.DPAD_RIGHT, KeyEvent.KEYCODE_D)
    }

    fun defaultsFor(family: ControllerFamily): Map<LogicalControl, Int> = when (family) {
        ControllerFamily.KEYBOARD -> keyboardDefaults()
        ControllerFamily.NINTENDO -> nintendoDefaults()
        ControllerFamily.PLAYSTATION -> playstationDefaults()
        ControllerFamily.XBOX,
        ControllerFamily.GENERIC_GAMEPAD,
        ControllerFamily.TOUCH_CONTROLLER,
        ControllerFamily.UNKNOWN -> gamepadDefaults()
    }

    /**
     * Prefer user/legacy bindings; fill missing logicals from family defaults.
     * Never drops existing mappings.
     */
    fun mergeWithLegacy(
        family: ControllerFamily,
        legacyLogicalToKey: Map<LogicalControl, Int>,
    ): Map<LogicalControl, Int> {
        val merged = legacyLogicalToKey.toMutableMap()
        defaultsFor(family).forEach { (logical, key) ->
            if (logical !in merged) merged[logical] = key
        }
        return merged
    }

    /** Convert InputBindingPrefs key→(bit,bank) into LogicalControl→keyCode. */
    fun fromInputBindingPrefs(prefs: Map<Int, Pair<Int, Int>>): Map<LogicalControl, Int> {
        val out = mutableMapOf<LogicalControl, Int>()
        prefs.forEach { (keyCode, pair) ->
            LogicalControl.entries.firstOrNull { it.bank == pair.second && it.bit == pair.first }
                ?.let { out[it] = keyCode }
        }
        return out
    }

    fun legacyOrDefaultLogicalMap(): Map<LogicalControl, Int> {
        val fromPrefs = fromInputBindingPrefs(InputBindingPrefs.loadBindings())
        return mergeWithLegacy(ControllerFamily.GENERIC_GAMEPAD, fromPrefs)
    }
}
