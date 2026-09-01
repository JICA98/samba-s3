package com.zenithblue.sambas3.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

data class LogicalPadState(
    val digital1: Int = 0,
    val digital2: Int = 0,
    val leftX: Int = 127,
    val leftY: Int = 127,
    val rightX: Int = 127,
    val rightY: Int = 127,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
)

class GamepadMapper(private var profile: ControllerProfile) {
    private var state = LogicalPadState()
    private val pressedKeys = mutableSetOf<Int>()
    fun current() = state
    fun profile() = profile
    fun reload(newProfile: ControllerProfile) {
        profile = newProfile
        state = LogicalPadState()
        pressedKeys.clear()
    }
    fun digitalBinding(keyCode: Int): Pair<Int, Int> = profile.digitalBindings.entries.firstOrNull { it.value == keyCode }?.let { it.key.bit to it.key.bank } ?: 0 to 0
    fun keyDown(keyCode: Int): LogicalPadState? = applyKey(keyCode, true)
    fun keyUp(keyCode: Int): LogicalPadState? = applyKey(keyCode, false)

    fun logicalForKey(keyCode: Int): LogicalControl? =
        profile.digitalBindings.entries.firstOrNull { it.value == keyCode }?.key

    /** User-facing action for diagnostics and the Test page, including virtual axes. */
    fun actionLabelForKey(keyCode: Int): String? {
        logicalForKey(keyCode)?.let { return if (it == LogicalControl.PS_HOME_FRONTEND) "Emulator Menu" else it.label }
        val analog = profile.keyboardAnalog ?: return null
        return when {
            analog.leftX.negativeKey == keyCode -> "Left Stick Left"
            analog.leftX.positiveKey == keyCode -> "Left Stick Right"
            analog.leftY.negativeKey == keyCode -> "Left Stick Up"
            analog.leftY.positiveKey == keyCode -> "Left Stick Down"
            analog.rightX.negativeKey == keyCode -> "Right Stick Left"
            analog.rightX.positiveKey == keyCode -> "Right Stick Right"
            analog.rightY.negativeKey == keyCode -> "Right Stick Up"
            analog.rightY.positiveKey == keyCode -> "Right Stick Down"
            else -> null
        }
    }

    fun isMappedKey(keyCode: Int): Boolean = actionLabelForKey(keyCode) != null

    private fun applyKey(keyCode: Int, down: Boolean): LogicalPadState? {
        val logical = logicalForKey(keyCode)
        val analog = profile.keyboardAnalog
        if (logical == null && actionLabelForKey(keyCode) == null) return null
        if (down) pressedKeys += keyCode else pressedKeys -= keyCode

        // PS/Guide is owned by the frontend menu gate, never guest gameplay.
        if (logical == LogicalControl.PS_HOME_FRONTEND) return null

        if (logical != null && logical != LogicalControl.PS_HOME_FRONTEND) {
            state = if (logical.bank == 0) {
                state.copy(
                    digital1 = if (down) state.digital1 or logical.bit else state.digital1 and logical.bit.inv(),
                    leftTrigger = if (logical == LogicalControl.L2) if (down) 1f else 0f else state.leftTrigger,
                    rightTrigger = if (logical == LogicalControl.R2) if (down) 1f else 0f else state.rightTrigger,
                )
            } else {
                state.copy(
                    digital2 = if (down) state.digital2 or logical.bit else state.digital2 and logical.bit.inv(),
                    leftTrigger = if (logical == LogicalControl.L2) if (down) 1f else 0f else state.leftTrigger,
                    rightTrigger = if (logical == LogicalControl.R2) if (down) 1f else 0f else state.rightTrigger,
                )
            }
        }
        if (analog != null) {
            state = state.copy(
                leftX = axisByte(analog.leftX),
                leftY = axisByte(analog.leftY),
                rightX = axisByte(analog.rightX),
                rightY = axisByte(analog.rightY),
            )
        }
        return state
    }

    private fun axisByte(pair: DigitalAxisPair): Int {
        val negative = pair.negativeKey?.let(pressedKeys::contains) == true
        val positive = pair.positiveKey?.let(pressedKeys::contains) == true
        return when {
            negative == positive -> 127
            negative -> 0
            else -> 255
        }
    }

    fun motion(event: MotionEvent): LogicalPadState {
        if (event.source and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD) == 0) return state
        val lx = tune(event.getAxisValue(profile.leftX.axis), event.getAxisValue(profile.leftY.axis), profile.leftStick)
        val rx = tune(event.getAxisValue(profile.rightX.axis), event.getAxisValue(profile.rightY.axis), profile.rightStick)
        var d1 = state.digital1 and (DigitalMask.dpad.inv())
        val hatX = event.getAxisValue(MotionAxis.HAT_X); val hatY = event.getAxisValue(MotionAxis.HAT_Y)
        if (hatX < -.1f) d1 = d1 or com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_LEFT.bit else if (hatX > .1f) d1 = d1 or com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
        if (hatY < -.1f) d1 = d1 or com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_UP.bit else if (hatY > .1f) d1 = d1 or com.zenithblue.sambas3.Digital1Flags.CELL_PAD_CTRL_DOWN.bit
        var d2 = state.digital2
        d2 = if (event.getAxisValue(profile.leftTrigger.axis) > profile.triggerThreshold) d2 or com.zenithblue.sambas3.Digital2Flags.CELL_PAD_CTRL_L2.bit else d2 and com.zenithblue.sambas3.Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        d2 = if (event.getAxisValue(profile.rightTrigger.axis) > profile.triggerThreshold) d2 or com.zenithblue.sambas3.Digital2Flags.CELL_PAD_CTRL_R2.bit else d2 and com.zenithblue.sambas3.Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        val lt = event.getAxisValue(profile.leftTrigger.axis).coerceIn(0f, 1f)
        val rt = event.getAxisValue(profile.rightTrigger.axis).coerceIn(0f, 1f)
        state = LogicalPadState(
            d1,
            d2,
            toByte(lx.first),
            toByte(lx.second),
            toByte(rx.first),
            toByte(rx.second),
            lt,
            rt,
        )
        return state
    }

    private fun tune(xRaw: Float, yRaw: Float, tuning: StickTuning): Pair<Float, Float> {
        var x = if (tuning.invertX) -xRaw else xRaw; var y = if (tuning.invertY) -yRaw else yRaw
        val radius = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (radius <= tuning.deadzone) return 0f to 0f
        val scale = ((radius - tuning.deadzone) / (1f - tuning.deadzone)).coerceIn(0f, 1f) / radius.coerceAtLeast(.001f)
        x = (x * scale).coerceIn(-1f, 1f); y = (y * scale).coerceIn(-1f, 1f)
        val curve = tuning.sensitivity.coerceIn(.25f, 3f)
        return x.signPow(curve) to y.signPow(curve)
    }
    private fun Float.signPow(power: Float) = if (this < 0) -((-this).pow(power)) else this.pow(power)
    private fun toByte(value: Float) = ((value.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(0, 255)
    object DigitalMask { const val dpad = 0xF0 }
}
