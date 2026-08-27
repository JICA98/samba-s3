package com.zenithblue.sambas3.ui.ingame

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** Semantic menu commands — the router maps raw Android events to these only. */
sealed interface MenuCommand {
    data object Previous : MenuCommand
    data object Next : MenuCommand
    data object Activate : MenuCommand
    data object Back : MenuCommand
    data object PageAction1 : MenuCommand // Square
    data object PageAction2 : MenuCommand // Triangle
    data object PageUp : MenuCommand      // L1
    data object PageDown : MenuCommand    // R1
    data object Left : MenuCommand
    data object Right : MenuCommand
    data object HomeToggle : MenuCommand
}

/**
 * Maps Android key/motion events to [MenuCommand]s. Does NOT consume inputs
 * twice: it either maps and reports a command, or reports not-handled.
 * Implements real analog edge/repeat: deadzone, initial edge, repeat delay,
 * repeat interval, and re-edge only after the stick returns inside the
 * deadzone.
 */
class InGameMenuInputRouter(
    private val onCommand: (MenuCommand) -> Boolean,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val deadzone: Float = 0.5f,
    private val repeatDelayMs: Long = 300,
    private val repeatIntervalMs: Long = 100
) {
    private enum class AxisState { Neutral, EdgePending, Repeating }

    private var vertical = AxisState.Neutral
    private var verticalValue = 0f
    private var nextRepeatAt = 0L

    fun handleKey(keyCode: Int, action: Int, event: KeyEvent?): Boolean {
        if (event == null || action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return isMenuInputKey(keyCode) // consume auto-repeat, no new command
        val command = mapKeyCode(keyCode) ?: return false
        return onCommand(command)
    }

    fun handleMotion(event: MotionEvent?): Boolean {
        if (event == null) return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        var handled = false

        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        handled = trackAxis(y) { if (y < 0) MenuCommand.Previous else MenuCommand.Next } || handled

        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatYActive = abs(hatY) > deadzone
        val hatXActive = abs(hatX) > deadzone
        if (hatYActive) {
            handled = onCommand(if (hatY < 0) MenuCommand.Previous else MenuCommand.Next) || handled
        }
        if (hatXActive) {
            handled = onCommand(if (hatX < 0) MenuCommand.Left else MenuCommand.Right) || handled
        }
        if (!hatYActive && !hatXActive) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            handled = trackHorizontal(x, y) || handled
        }
        return handled
    }

    private var horizontal = AxisState.Neutral

    private fun trackHorizontal(x: Float, y: Float): Boolean {
        val active = abs(x) > deadzone && abs(y) <= deadzone
        val now = clockMs()
        when (horizontal) {
            AxisState.Neutral -> if (active) {
                horizontal = AxisState.EdgePending
                nextRepeatAt = now + repeatDelayMs
                return onCommand(if (x < 0) MenuCommand.Left else MenuCommand.Right)
            }

            AxisState.EdgePending, AxisState.Repeating -> {
                if (!active) {
                    horizontal = AxisState.Neutral
                    return false
                }
                if (now >= nextRepeatAt) {
                    nextRepeatAt = now + repeatIntervalMs
                    horizontal = AxisState.Repeating
                    return onCommand(if (x < 0) MenuCommand.Left else MenuCommand.Right)
                }
            }
        }
        return false
    }

    private fun trackAxis(value: Float, emit: () -> MenuCommand): Boolean {
        val active = abs(value) > deadzone
        val now = clockMs()
        when (vertical) {
            AxisState.Neutral -> if (active) {
                vertical = AxisState.EdgePending
                verticalValue = value
                nextRepeatAt = now + repeatDelayMs
                return onCommand(emit())
            }

            AxisState.EdgePending, AxisState.Repeating -> {
                if (!active) {
                    vertical = AxisState.Neutral
                    return false
                }
                if (now >= nextRepeatAt) {
                    nextRepeatAt = now + repeatIntervalMs
                    vertical = AxisState.Repeating
                    return onCommand(emit())
                }
            }
        }
        return false
    }

    /** Menu closes / Activity stops — cancel any pending repeat. */
    fun cancelRepeat() {
        vertical = AxisState.Neutral
        horizontal = AxisState.Neutral
        nextRepeatAt = 0L
    }

    private fun mapKeyCode(keyCode: Int): MenuCommand? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> MenuCommand.Previous
        KeyEvent.KEYCODE_DPAD_DOWN -> MenuCommand.Next
        KeyEvent.KEYCODE_DPAD_LEFT -> MenuCommand.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> MenuCommand.Right
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> MenuCommand.Activate
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> MenuCommand.Back
        KeyEvent.KEYCODE_BUTTON_X -> MenuCommand.PageAction1
        KeyEvent.KEYCODE_BUTTON_Y -> MenuCommand.PageAction2
        KeyEvent.KEYCODE_BUTTON_L1 -> MenuCommand.PageUp
        KeyEvent.KEYCODE_BUTTON_R1 -> MenuCommand.PageDown
        else -> null
    }

    fun isMenuInputKey(keyCode: Int): Boolean = mapKeyCode(keyCode) != null ||
        keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE
}
