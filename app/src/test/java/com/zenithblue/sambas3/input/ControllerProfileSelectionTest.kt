package com.zenithblue.sambas3.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerProfileSelectionTest {

    private fun profile(
        id: String,
        deviceKey: String? = null,
        descriptor: String? = null,
        vendorId: Int? = null,
        productId: Int? = null,
        bindings: Map<LogicalControl, Int> = mapOf(LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_A),
    ) = ControllerProfile(
        id = id,
        name = id,
        deviceKey = deviceKey,
        deviceDescriptor = descriptor,
        vendorId = vendorId,
        productId = productId,
        digitalBindings = bindings,
    )

    @Test
    fun selectsByStableDeviceKey() {
        val saved = profile("p1", deviceKey = "desc:abc", bindings = mapOf(LogicalControl.CIRCLE to KeyEvent.KEYCODE_BUTTON_B))
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = listOf(saved, profile("other", deviceKey = "desc:zzz")),
            deviceKey = "desc:abc",
            family = ControllerFamily.PLAYSTATION,
        )
        assertEquals("p1", selected.id)
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, selected.digitalBindings[LogicalControl.CIRCLE])
    }

    @Test
    fun fallsBackToDescriptorThenAttachesDeviceKey() {
        val saved = profile("legacy", descriptor = "usb-1")
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = listOf(saved),
            deviceKey = "desc:usb-1",
            family = ControllerFamily.XBOX,
            descriptor = "usb-1",
        )
        assertEquals("legacy", selected.id)
        assertEquals("desc:usb-1", selected.deviceKey)
    }

    @Test
    fun fallsBackToVidPid() {
        val saved = profile("vid", vendorId = 0x045E, productId = 0x0B13)
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = listOf(saved),
            deviceKey = "vidpid:1118:2835:xbox",
            family = ControllerFamily.XBOX,
            vendorId = 0x045E,
            productId = 0x0B13,
        )
        assertEquals("vid", selected.id)
    }

    @Test
    fun buildsFamilyDefaultWhenNoMatch() {
        val selected = ControllerProfileSelection.selectForDevice(
            profiles = emptyList(),
            deviceKey = "desc:new",
            family = ControllerFamily.KEYBOARD,
        )
        assertEquals(ControllerFamily.KEYBOARD, selected.family)
        assertEquals("desc:new", selected.deviceKey)
        assertEquals(KeyEvent.KEYCODE_W, selected.digitalBindings[LogicalControl.DPAD_UP])
        assertTrue(selected.isDefault)
    }

    @Test
    fun migrateLegacyKeepsExistingBindingsAndFillsGaps() {
        val legacy = ControllerProfile(
            id = "default",
            name = "Default",
            digitalBindings = mapOf(LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_C),
        )
        val migrated = ControllerProfileSelection.migrateLegacy(
            legacy = legacy,
            deviceKey = "desc:pad",
            family = ControllerFamily.PLAYSTATION,
            descriptor = "pad",
        )
        assertEquals(KeyEvent.KEYCODE_BUTTON_C, migrated.digitalBindings[LogicalControl.CROSS])
        assertTrue(migrated.digitalBindings.containsKey(LogicalControl.CIRCLE))
        assertEquals("desc:pad", migrated.deviceKey)
        assertNotEquals("default", migrated.id)
        assertFalse(migrated.isDefault)
    }

    @Test
    fun reconnectSameDescriptorRestoresSameProfile() {
        val deviceKey = ControllerDeviceKey.stableKey("usb:1", 1, 2, "Pad", deviceId = 10)
        val saved = profile("mine", deviceKey = deviceKey, bindings = mapOf(LogicalControl.START to KeyEvent.KEYCODE_BUTTON_START))
        val afterReconnectKey = ControllerDeviceKey.stableKey("usb:1", 1, 2, "Pad", deviceId = 55)
        assertEquals(deviceKey, afterReconnectKey)
        val selected = ControllerProfileSelection.selectForDevice(listOf(saved), afterReconnectKey, ControllerFamily.GENERIC_GAMEPAD)
        assertEquals("mine", selected.id)
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, selected.digitalBindings[LogicalControl.START])
    }
}
