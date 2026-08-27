package com.zenithblue.sambas3.ui.ingame

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Regression tests for §11: re-arm must be decided by the REAL physical
 * state, never by the artificially neutralized forwarded pad state.
 */
class GameplayInputGateTest {

    private fun keyEvent(): KeyEvent = Mockito.mock(KeyEvent::class.java)

    private fun motionEvent(vararg axes: Pair<Int, Float>): MotionEvent {
        val m = Mockito.mock(MotionEvent::class.java)
        Mockito.`when`(m.source).thenReturn(android.view.InputDevice.SOURCE_JOYSTICK)
        Mockito.`when`(m.action).thenReturn(MotionEvent.ACTION_MOVE)
        for ((axis, v) in axes) {
            Mockito.`when`(m.getAxisValue(axis)).thenReturn(v)
        }
        // Unset axes default to 0f
        return m
    }

    @Test
    fun hold_cross_while_closing_does_not_leak_into_game() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        // Player holds Cross (physical down) while menu closes
        tracker.onKeyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        gate.waitForNeutral()
        // Held key events keep arriving -> still consumed, core stays neutral
        assertFalse(gate.onPhysicalEvent())
        // Key released
        tracker.onKeyEvent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP)
        assertTrue(gate.onPhysicalEvent())
        assertEquals(GameplayInputGate.Mode.ARMED, gate.mode)
        // Only the NEXT new press reaches the game
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun hold_start_blocks_rearm_until_release() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        tracker.onKeyEvent(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN)
        gate.waitForNeutral()
        assertFalse(gate.onPhysicalEvent())
        tracker.onKeyEvent(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_UP)
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun hold_triggers_block_rearm_until_axis_reports_release() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        tracker.onMotionEvent(motionEvent(android.view.MotionEvent.AXIS_LTRIGGER to 0.8f, android.view.MotionEvent.AXIS_RTRIGGER to 0.6f))
        gate.waitForNeutral()
        assertFalse(gate.onPhysicalEvent())
        tracker.onMotionEvent(motionEvent(android.view.MotionEvent.AXIS_LTRIGGER to 0f, android.view.MotionEvent.AXIS_RTRIGGER to 0f))
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun held_stick_blocks_rearm_until_centered() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        tracker.onMotionEvent(motionEvent(android.view.MotionEvent.AXIS_X to 0.9f))
        gate.waitForNeutral()
        assertFalse(gate.onPhysicalEvent())
        tracker.onMotionEvent(motionEvent(android.view.MotionEvent.AXIS_X to 0.05f))
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun held_dpad_blocks_rearm() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        tracker.onKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN)
        gate.waitForNeutral()
        assertFalse(gate.onPhysicalEvent())
        tracker.onKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP)
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun arming_from_scratch_is_immediate() {
        val tracker = PhysicalInputTracker()
        val gate = GameplayInputGate(tracker)
        tracker.onMotionEvent(motionEvent())
        tracker.onKeyEvent(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_UP)
        gate.waitForNeutral()
        assertTrue(gate.onPhysicalEvent())
    }

    @Test
    fun armed_gate_always_passes() {
        val gate = GameplayInputGate(PhysicalInputTracker())
        assertTrue(gate.onPhysicalEvent())
    }
}
