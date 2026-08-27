package com.zenithblue.sambas3.ui.ingame

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

sealed interface MenuInput {
    data object Up : MenuInput
    data object Down : MenuInput
    data object Left : MenuInput
    data object Right : MenuInput
    data object Confirm : MenuInput       // Cross / A
    data object Back : MenuInput          // Circle / B
    data object Square : MenuInput
    data object Triangle : MenuInput
    data object PageUp : MenuInput        // L1
    data object PageDown : MenuInput      // R1
    data object Home : MenuInput
}

class InGameMenuInputRouter(
    private val controller: InGameMenuController,
    private val onInput: (MenuInput) -> Boolean = { false }
) {
    private var repeatJob: Runnable? = null
    private val deadzone = 0.5f

    fun handleKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return false
        // Map known keys to menu inputs
        val input = mapKeyCode(keyCode, event) ?: return false
        return dispatch(input)
    }

    fun handleKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // For menu we consume key up as well to avoid leak
        val input = mapKeyCode(keyCode, event) ?: return false
        // Only handle back on up? But we handle on down for responsiveness.
        return true
    }

    fun handleGenericMotion(event: MotionEvent?): Boolean {
        if (event == null) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        // Left stick deadzone
        var handled = false
        if (abs(y) > deadzone) {
            if (y < 0) dispatch(MenuInput.Up) else dispatch(MenuInput.Down)
            handled = true
        }
        if (abs(x) > deadzone) {
            if (x < 0) dispatch(MenuInput.Left) else dispatch(MenuInput.Right)
            handled = true
        }
        // Hat for dpad via motion is handled via key events, but also handle here
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (abs(hatY) > deadzone) {
            if (hatY < 0) dispatch(MenuInput.Up) else dispatch(MenuInput.Down)
            handled = true
        }
        if (abs(hatX) > deadzone) {
            if (hatX < 0) dispatch(MenuInput.Left) else dispatch(MenuInput.Right)
            handled = true
        }
        return handled
    }

    private fun mapKeyCode(keyCode: Int, event: KeyEvent?): MenuInput? {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> MenuInput.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> MenuInput.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> MenuInput.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> MenuInput.Right
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> MenuInput.Confirm
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuInput.Back
            KeyEvent.KEYCODE_BUTTON_X -> MenuInput.Square
            KeyEvent.KEYCODE_BUTTON_Y -> MenuInput.Triangle
            KeyEvent.KEYCODE_BUTTON_L1 -> MenuInput.PageUp
            KeyEvent.KEYCODE_BUTTON_R1 -> MenuInput.PageDown
            KeyEvent.KEYCODE_BUTTON_START -> MenuInput.Confirm
            KeyEvent.KEYCODE_BUTTON_SELECT -> MenuInput.Back
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_MODE -> MenuInput.Home
            else -> null
        }
    }

    private fun dispatch(input: MenuInput): Boolean {
        // First try custom handler
        if (onInput(input)) return true
        // Default handling: navigation
        when (input) {
            is MenuInput.Up -> controller.moveSelection(-1, 20) // max will be clamped by UI layer
            is MenuInput.Down -> controller.moveSelection(1, 20)
            is MenuInput.Back -> { controller.back(); }
            is MenuInput.Confirm -> { /* let UI confirm */ }
            else -> {}
        }
        return true
    }

    fun isMenuInputKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_MODE -> true
            else -> false
        }
    }
}
