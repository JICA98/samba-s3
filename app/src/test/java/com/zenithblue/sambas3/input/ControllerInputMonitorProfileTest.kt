package com.zenithblue.sambas3.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Controls must drive [ControllerInputMonitor] with the selected device profile,
 * not only the global `load()` current profile.
 */
class ControllerInputMonitorProfileTest {

    @Test
    fun reloadProfileUsesExplicitDeviceBindingsForLiveMapping() {
        val deviceProfile = ControllerProfile(
            id = "device:test",
            name = "Device",
            deviceKey = "desc:test",
            family = ControllerFamily.XBOX,
            digitalBindings = mapOf(
                LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_A,
                LogicalControl.CIRCLE to KeyEvent.KEYCODE_BUTTON_C, // unusual binding to prove we are not on defaults alone
            ),
        )
        try {
            ControllerInputMonitor.setMode(ControllerMonitorMode.Test)
            ControllerInputMonitor.reloadProfile(deviceProfile)
            assertEquals("device:test", ControllerInputMonitor.activeProfile().id)
            assertEquals(KeyEvent.KEYCODE_BUTTON_C, ControllerInputMonitor.activeProfile().digitalBindings[LogicalControl.CIRCLE])

            val other = deviceProfile.copy(
                id = "other",
                digitalBindings = mapOf(LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_X),
            )
            ControllerInputMonitor.reloadProfile(other)
            assertEquals("other", ControllerInputMonitor.activeProfile().id)
            assertEquals(KeyEvent.KEYCODE_BUTTON_X, ControllerInputMonitor.activeProfile().digitalBindings[LogicalControl.CROSS])
            assertNotEquals(KeyEvent.KEYCODE_BUTTON_A, ControllerInputMonitor.activeProfile().digitalBindings[LogicalControl.CROSS])
        } finally {
            ControllerInputMonitor.setMode(ControllerMonitorMode.Off)
        }
    }
}
