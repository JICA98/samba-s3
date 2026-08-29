package com.zenithblue.sambas3.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.zenithblue.sambas3.Digital1Flags
import com.zenithblue.sambas3.Digital2Flags
import com.zenithblue.sambas3.utils.GeneralSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PadOverlayInteractionResetTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        GeneralSettings.init(context)
    }

    @Test
    fun button_cancel_clears_pressed_lock_bits_and_restores_alpha() {
        val button = PadOverlayButton(
            context.resources,
            Digital1Flags.CELL_PAD_CTRL_PS.bit,
            0,
            GlassButtonType.PS_HOME,
            "PS"
        )
        button.setOpacity(50)
        val state = State()
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        button.onTouch(down, 0, state)
        down.recycle()

        assertEquals(Digital1Flags.CELL_PAD_CTRL_PS.bit, state.digital[0])
        assertEquals(255, button.getAlpha())
        assert(button.isPressedForTest)

        button.cancelInteraction(state)
        button.cancelInteraction(state)

        assertFalse(button.isPressedForTest)
        assertEquals(-1, button.lockedPointerForTest)
        assertEquals(0, state.digital[0])
        assertEquals(128, button.getAlpha())
    }

    @Test
    fun dpad_cancel_clears_all_owned_bits_and_pointer_slots() {
        val empty = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val dpad = PadOverlayDpad(
            context.resources, 40, 40, "dpad", Rect(0, 0, 120, 120), 0,
            empty, 0x10, empty, 0x20, empty, 0x40, empty, 0x80, false
        )
        val state = State(intArrayOf(0x10 or 0x20 or 0x40 or 0x80, 0))

        dpad.cancelInteraction(state)
        dpad.cancelInteraction(state)

        assertEquals(0, state.digital[0])
        empty.recycle()
    }

    @Test
    fun stick_cancel_returns_analog_and_l3_state_to_neutral() {
        val empty = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val stick = PadOverlayStick(
            context.resources, true, empty, empty,
            pressDigitalIndex = 0, pressBit = Digital1Flags.CELL_PAD_CTRL_L3.bit
        )
        stick.setBounds(0, 0, 100, 100)
        val state = State()
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        stick.onTouch(down, 0, state)
        down.recycle()
        state.leftStickX = 255
        state.leftStickY = 0

        stick.cancelInteraction(state)

        assertFalse(stick.isActive())
        assertEquals(0, state.digital[0])
        assertEquals(127, state.leftStickX)
        assertEquals(127, state.leftStickY)
        empty.recycle()
    }
}
