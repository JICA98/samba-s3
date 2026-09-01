package com.zenithblue.sambas3.input

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerKeyboardRepairTest {
    @Test
    fun ambiguousOptimusKeyboardUsesAlphabeticIdentity() {
        val result = ControllerClassifier.classify(
            ControllerClassifier.InputDeviceMetadata(
                name = "Optimus 1 Keyboard",
                vendorId = 0x32C2,
                productId = 0x6621,
                sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
        assertEquals(InputDeviceType.KEYBOARD, result.first)
        assertEquals(ControllerFamily.KEYBOARD, result.second)
    }

    @Test
    fun controllerIdentityWinsOverKeyboardSource() {
        val result = ControllerClassifier.classify(
            ControllerClassifier.InputDeviceMetadata(
                name = "DualSense Wireless Controller",
                vendorId = 0x054C,
                productId = 0x0CE6,
                sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_GAMEPAD,
                keyboardType = InputDevice.KEYBOARD_TYPE_ALPHABETIC,
            ),
        )
        assertEquals(InputDeviceType.GAMEPAD, result.first)
        assertEquals(ControllerFamily.PLAYSTATION, result.second)
    }

    @Test
    fun newKeyboardDefaultsDoNotInheritGamepadBindings() {
        val profile = ControllerProfileSelection.buildDefault("desc:optimus", ControllerFamily.KEYBOARD)
        assertEquals(KeyEvent.KEYCODE_J, profile.digitalBindings[LogicalControl.CROSS])
        assertEquals(KeyEvent.KEYCODE_ENTER, profile.digitalBindings[LogicalControl.START])
        assertEquals(KeyEvent.KEYCODE_A, profile.keyboardAnalog?.leftX?.negativeKey)
        assertEquals(KeyEvent.KEYCODE_D, profile.keyboardAnalog?.leftX?.positiveKey)
        assertTrue(profile.digitalBindings.values.none { it == KeyEvent.KEYCODE_BUTTON_A })
    }

    @Test
    fun generatedMisclassifiedDefaultIsRegeneratedForKeyboard() {
        val old = ControllerProfile(
            id = "device:optimus",
            deviceKey = "desc:optimus",
            family = ControllerFamily.GENERIC_GAMEPAD,
            digitalBindings = FamilyDefaultMappings.gamepadDefaults(),
            isDefault = true,
        )
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = listOf(old),
            deviceKey = "desc:optimus",
            family = ControllerFamily.KEYBOARD,
        )
        assertEquals(ControllerFamily.KEYBOARD, selected.family)
        assertEquals("PC Gamepad", selected.name)
        assertEquals(KeyEvent.KEYCODE_J, selected.digitalBindings[LogicalControl.CROSS])
    }

    @Test
    fun keyboardAnalogKeysHandleOppositionAndRelease() {
        val mapper = GamepadMapper(ControllerProfileSelection.buildDefault("kb", ControllerFamily.KEYBOARD))
        assertNotNull(mapper.keyDown(KeyEvent.KEYCODE_W))
        assertEquals(0, mapper.current().leftY)
        mapper.keyDown(KeyEvent.KEYCODE_S)
        assertEquals(127, mapper.current().leftY)
        mapper.keyUp(KeyEvent.KEYCODE_W)
        assertEquals(255, mapper.current().leftY)
        mapper.keyUp(KeyEvent.KEYCODE_S)
        assertEquals(127, mapper.current().leftY)
        mapper.keyDown(KeyEvent.KEYCODE_D)
        assertEquals(255, mapper.current().leftX)
        mapper.keyUp(KeyEvent.KEYCODE_D)
        assertEquals(127, mapper.current().leftX)
    }

    @Test
    fun keyboardFaceAndSystemKeysAreMapped() {
        val mapper = GamepadMapper(ControllerProfileSelection.buildDefault("kb", ControllerFamily.KEYBOARD))
        mapper.keyDown(KeyEvent.KEYCODE_J)
        assertTrue(mapper.current().digital2 and LogicalControl.CROSS.bit != 0)
        mapper.keyUp(KeyEvent.KEYCODE_J)
        assertEquals(0, mapper.current().digital2 and LogicalControl.CROSS.bit)
        assertEquals(LogicalControl.START, mapper.logicalForKey(KeyEvent.KEYCODE_ENTER))
        assertEquals("Emulator Menu", mapper.actionLabelForKey(KeyEvent.KEYCODE_ESCAPE))
        assertNull(mapper.actionLabelForKey(KeyEvent.KEYCODE_F))
    }

    @Test
    fun startHoldRequiresFiveSecondsAndCompletesOnce() {
        val tracker = StartHoldTracker()
        assertEquals(0f, tracker.update(true, 1_000L).progress)
        assertEquals(0.5f, tracker.update(true, 3_500L).progress, 0.001f)
        assertTrue(!tracker.update(true, 5_999L).completed)
        assertTrue(tracker.update(true, 6_000L).completed)
        assertTrue(!tracker.update(true, 7_000L).completed)
        assertEquals(0f, tracker.update(false, 7_001L).progress)
        assertTrue(tracker.update(true, 7_002L).progress == 0f)
    }
}
