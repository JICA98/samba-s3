package com.zenithblue.sambas3

import android.view.KeyEvent
import com.zenithblue.sambas3.input.ControllerProfile
import com.zenithblue.sambas3.input.GamepadMapper
import com.zenithblue.sambas3.input.LogicalControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMapperTest {
    private val profile = ControllerProfile(
        digitalBindings = mapOf(
            LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_A,
            LogicalControl.CIRCLE to KeyEvent.KEYCODE_BUTTON_B,
            LogicalControl.PS_HOME_FRONTEND to KeyEvent.KEYCODE_BUTTON_MODE
        )
    )

    @Test fun digitalDownUpPreservesOtherBits() {
        val mapper = GamepadMapper(profile)
        assertEquals(Digital2Flags.CELL_PAD_CTRL_CROSS.bit, mapper.keyDown(KeyEvent.KEYCODE_BUTTON_A)?.digital2)
        assertEquals(0, mapper.keyUp(KeyEvent.KEYCODE_BUTTON_A)?.digital2)
    }

    @Test fun frontendHomeIsReservedFromGuestState() {
        val mapper = GamepadMapper(profile)
        assertEquals(null, mapper.keyDown(KeyEvent.KEYCODE_BUTTON_MODE))
        assertEquals(0, mapper.current().digital1 and Digital1Flags.CELL_PAD_CTRL_PS.bit)
    }

    @Test fun radialDeadzoneCentersStick() {
        val mapper = GamepadMapper(profile.copy(leftStick = com.zenithblue.sambas3.input.StickTuning(deadzone = .2f)))
        assertEquals(127, mapper.current().leftX)
        assertEquals(127, mapper.current().leftY)
    }
}
