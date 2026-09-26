package com.zenithblue.sambas3.input

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RightStickAxisTest {
    @Test fun alternatePairRequiresMissingConfiguredAxisAndCompleteAlternatePair() {
        val z = MotionEvent.AXIS_Z
        val rz = MotionEvent.AXIS_RZ
        val rx = MotionEvent.AXIS_RX
        val ry = MotionEvent.AXIS_RY
        assertFalse(useAlternateRightStick(listOf(z, rz, rx, ry), z, rz))
        assertTrue(useAlternateRightStick(listOf(rx, ry), z, rz))
        assertFalse(useAlternateRightStick(listOf(rx), z, rz))
        assertFalse(useAlternateRightStick(emptyList(), z, rz))
    }
}
