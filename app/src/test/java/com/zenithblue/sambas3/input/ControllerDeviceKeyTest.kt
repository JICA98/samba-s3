package com.zenithblue.sambas3.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDeviceKeyTest {

    @Test
    fun prefersDescriptorOverTransientId() {
        val key = ControllerDeviceKey.stableKey(
            descriptor = "usb:054c:0ce6",
            vendorId = 0x054C,
            productId = 0x0CE6,
            name = "DualSense",
            deviceId = 42,
        )
        assertTrue(key.startsWith("desc:"))
        assertFalse(key.contains("id:42"))
        // Same device reconnects with a new Android deviceId → same key
        val reconnected = ControllerDeviceKey.stableKey(
            descriptor = "usb:054c:0ce6",
            vendorId = 0x054C,
            productId = 0x0CE6,
            name = "DualSense",
            deviceId = 99,
        )
        assertEquals(key, reconnected)
    }

    @Test
    fun fallsBackToVidPidName() {
        val key = ControllerDeviceKey.stableKey(
            descriptor = null,
            vendorId = 0x045E,
            productId = 0x0B13,
            name = "Xbox Wireless Controller",
            deviceId = 7,
        )
        assertTrue(key.startsWith("vidpid:"))
        assertTrue(key.contains("1118:2835") || key.contains("0x") || key.contains("045e") || key.contains("1118"))
        // vendorId Int string form
        assertEquals(
            "vidpid:1118:2835:xbox wireless controller",
            key,
        )
    }

    @Test
    fun normalizesWhitespaceInName() {
        val a = ControllerDeviceKey.stableKey(null, 1, 2, "Foo   Bar")
        val b = ControllerDeviceKey.stableKey(null, 1, 2, "foo bar")
        assertEquals(a, b)
    }

    @Test
    fun lastResortUsesTransientId() {
        val key = ControllerDeviceKey.stableKey(
            descriptor = "  ",
            vendorId = 0,
            productId = 0,
            name = "Mystery",
            deviceId = 3,
        )
        assertTrue(key.startsWith("id:3:"))
    }

    @Test
    fun connectedInputDeviceExposesStableKey() {
        val device = ControllerDeviceRepository.fromMetadata(
            deviceId = 5,
            name = "DualSense Wireless Controller",
            vendorId = 0x054C,
            productId = 0x0CE6,
            descriptor = "stable-desc-1",
            sources = android.view.InputDevice.SOURCE_GAMEPAD,
        )
        assertEquals("desc:stable-desc-1", device.deviceKey)
        assertEquals(ControllerFamily.PLAYSTATION, device.family)
    }

    @Test
    fun systemNoiseDevicesFilteredByName() {
        assertTrue(ControllerDeviceRepository.isSystemNoiseDevice("pmic_resin"))
        assertTrue(ControllerDeviceRepository.isSystemNoiseDevice("qcom-hv-haptics"))
        assertTrue(ControllerDeviceRepository.isSystemNoiseDevice("gpio-keys"))
        assertTrue(ControllerDeviceRepository.isSystemNoiseDevice("touchpanel_pen"))
        assertFalse(ControllerDeviceRepository.isSystemNoiseDevice("USB Keyboard"))
        assertFalse(ControllerDeviceRepository.isSystemNoiseDevice("DualSense Wireless Controller"))
    }
}
