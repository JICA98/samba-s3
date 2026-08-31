package com.zenithblue.sambas3.input

import android.view.InputDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerClassifierTest {

    @Test
    fun dualSenseIsPlayStation() {
        val (type, family) = ControllerClassifier.classify(
            name = "DualSense Wireless Controller",
            vendorId = 0x054C,
            productId = 0x0CE6,
            sources = InputDevice.SOURCE_GAMEPAD,
        )
        assertEquals(InputDeviceType.GAMEPAD, type)
        assertEquals(ControllerFamily.PLAYSTATION, family)
    }

    @Test
    fun dualShockNameHeuristic() {
        val (_, family) = ControllerClassifier.classify("Sony DualShock 4", sources = InputDevice.SOURCE_GAMEPAD)
        assertEquals(ControllerFamily.PLAYSTATION, family)
    }

    @Test
    fun xboxWirelessIsXbox() {
        val (_, family) = ControllerClassifier.classify(
            name = "Xbox Wireless Controller",
            vendorId = 0x045E,
            productId = 0x0B13,
            sources = InputDevice.SOURCE_GAMEPAD,
        )
        assertEquals(ControllerFamily.XBOX, family)
    }

    @Test
    fun switchProIsNintendo() {
        val (_, family) = ControllerClassifier.classify(
            name = "Pro Controller",
            vendorId = 0x057E,
            productId = 0x2009,
            sources = InputDevice.SOURCE_GAMEPAD,
        )
        assertEquals(ControllerFamily.NINTENDO, family)
    }

    @Test
    fun keyboardClassifiedAsKeyboardNotGamepad() {
        val (type, family) = ControllerClassifier.classify(
            name = "USB Keyboard",
            sources = InputDevice.SOURCE_KEYBOARD,
        )
        assertEquals(InputDeviceType.KEYBOARD, type)
        assertEquals(ControllerFamily.KEYBOARD, family)
    }

    @Test
    fun unknownHidFallsBackToGenericGamepad() {
        val (type, family) = ControllerClassifier.classify(
            name = "Generic USB Joystick",
            vendorId = 0x1234,
            productId = 0x5678,
            sources = InputDevice.SOURCE_JOYSTICK,
        )
        assertEquals(InputDeviceType.GAMEPAD, type)
        assertEquals(ControllerFamily.GENERIC_GAMEPAD, family)
    }

    @Test
    fun nameOnlyFixturesWithoutSources() {
        assertEquals(ControllerFamily.PLAYSTATION, ControllerClassifier.classify("DualSense").second)
        assertEquals(ControllerFamily.XBOX, ControllerClassifier.classify("Xbox 360 Controller").second)
        assertEquals(ControllerFamily.NINTENDO, ControllerClassifier.classify("Nintendo Switch Pro Controller").second)
        assertEquals(ControllerFamily.KEYBOARD, ControllerClassifier.classify("AT Translated Set 2 keyboard").second)
    }
}
