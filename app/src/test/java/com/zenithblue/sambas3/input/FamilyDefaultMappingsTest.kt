package com.zenithblue.sambas3.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyDefaultMappingsTest {

    @Test
    fun keyboardDefaultsAreKeysNotGamepadButtons() {
        val map = FamilyDefaultMappings.keyboardDefaults()
        assertEquals(KeyEvent.KEYCODE_W, map[LogicalControl.DPAD_UP])
        assertEquals(KeyEvent.KEYCODE_J, map[LogicalControl.CROSS])
        assertEquals(KeyEvent.KEYCODE_ENTER, map[LogicalControl.START])
        assertEquals(KeyEvent.KEYCODE_ESCAPE, map[LogicalControl.PS_HOME_FRONTEND])
        assertFalse(map.values.any { it == KeyEvent.KEYCODE_BUTTON_A })
    }

    @Test
    fun gamepadDefaultsAlignWithAndroidFaceButtons() {
        val map = FamilyDefaultMappings.gamepadDefaults()
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, map[LogicalControl.CROSS])
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, map[LogicalControl.CIRCLE])
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, map[LogicalControl.SQUARE])
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, map[LogicalControl.TRIANGLE])
    }

    @Test
    fun mergeNeverDropsLegacyBindings() {
        val legacy = mapOf(LogicalControl.CROSS to KeyEvent.KEYCODE_SPACE)
        val merged = FamilyDefaultMappings.mergeWithLegacy(ControllerFamily.XBOX, legacy)
        assertEquals(KeyEvent.KEYCODE_SPACE, merged[LogicalControl.CROSS])
        assertTrue(merged.containsKey(LogicalControl.CIRCLE))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, merged[LogicalControl.CIRCLE])
    }

    @Test
    fun fromInputBindingPrefsInvertsPhysicalToLogical() {
        val prefs = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Pair(LogicalControl.CROSS.bit, LogicalControl.CROSS.bank),
            KeyEvent.KEYCODE_BUTTON_B to Pair(LogicalControl.CIRCLE.bit, LogicalControl.CIRCLE.bank),
        )
        val logical = FamilyDefaultMappings.fromInputBindingPrefs(prefs)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, logical[LogicalControl.CROSS])
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, logical[LogicalControl.CIRCLE])
    }

    @Test
    fun defaultsForRoutesByFamily() {
        assertEquals(
            FamilyDefaultMappings.keyboardDefaults(),
            FamilyDefaultMappings.defaultsFor(ControllerFamily.KEYBOARD),
        )
        assertEquals(
            FamilyDefaultMappings.gamepadDefaults()[LogicalControl.CROSS],
            FamilyDefaultMappings.defaultsFor(ControllerFamily.XBOX)[LogicalControl.CROSS],
        )
    }
}
