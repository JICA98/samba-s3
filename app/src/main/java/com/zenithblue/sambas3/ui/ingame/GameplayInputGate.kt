package com.zenithblue.sambas3.ui.ingame

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Tracks the REAL physical controller state from raw Android events.
 *
 * This must never be confused with the forwarded/emulated pad state
 * (which gets artificially neutralized when the menu opens). Re-arm
 * decisions after closing the menu are made exclusively from this
 * tracker so that a held button/stick cannot instantly leak into the
 * game (plan §11).
 */
class PhysicalInputTracker(
    private val stickDeadzone: Float = 0.2f,
    private val triggerThreshold: Float = 0.1f
) {
    private val pressedKeys = mutableSetOf<Int>()
    private var l2 = 0f
    private var r2 = 0f
    private var hatX = 0f
    private var hatY = 0f
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f

    fun onKeyEvent(keyCode: Int, action: Int): Boolean {
        val gamepadish = when (keyCode) {
            in BUTTON_SET -> true
            else -> keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        }
        if (!gamepadish) return false
        when (action) {
            KeyEvent.ACTION_DOWN -> pressedKeys += keyCode
            KeyEvent.ACTION_UP -> pressedKeys -= keyCode
        }
        return true
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        l2 = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
        r2 = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
        hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        lx = event.getAxisValue(MotionEvent.AXIS_X)
        ly = event.getAxisValue(MotionEvent.AXIS_Y)
        rx = event.getAxisValue(MotionEvent.AXIS_Z)
        ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        return true
    }

    fun isPhysicalNeutral(): Boolean =
        pressedKeys.isEmpty() &&
            l2 < triggerThreshold && r2 < triggerThreshold &&
            abs(hatX) < triggerThreshold && abs(hatY) < triggerThreshold &&
            abs(lx) < stickDeadzone && abs(ly) < stickDeadzone &&
            abs(rx) < stickDeadzone && abs(ry) < stickDeadzone

    fun reset() {
        pressedKeys.clear()
        l2 = 0f; r2 = 0f; hatX = 0f; hatY = 0f; lx = 0f; ly = 0f; rx = 0f; ry = 0f
    }

    companion object {
        private val BUTTON_SET = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR
        )
    }
}

/**
 * Gameplay input gate: ARMED while playing, WAITING_FOR_PHYSICAL_NEUTRAL
 * after menu close. Only an actual hardware event proving full physical
 * neutrality transitions back to ARMED.
 */
class GameplayInputGate(private val tracker: PhysicalInputTracker) {
    enum class Mode { ARMED, WAITING_FOR_NEUTRAL }

    var mode: Mode = Mode.ARMED
        private set

    fun waitForNeutral() {
        mode = Mode.WAITING_FOR_NEUTRAL
    }

    fun arm() {
        mode = Mode.ARMED
    }

    /** Called on every physical event while waiting; true when re-armed. */
    fun onPhysicalEvent(): Boolean {
        if (mode == Mode.ARMED) return true
        if (tracker.isPhysicalNeutral()) {
            mode = Mode.ARMED
            return true
        }
        return false
    }

    val waitingForNeutral: Boolean get() = mode == Mode.WAITING_FOR_NEUTRAL
}
