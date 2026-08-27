package com.zenithblue.sambas3.ui.ingame

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class InGameMenuInputRouterTest {

    private class Harness {
        val commands = mutableListOf<MenuCommand>()
        var now = 0L
        val router = InGameMenuInputRouter(
            onCommand = { c ->
                commands.add(c)
                true
            },
            clockMs = { now }
        )
    }

    private fun keyEvent(action: Int, repeat: Int = 0): KeyEvent =
        Mockito.mock(KeyEvent::class.java).apply {
            Mockito.`when`(this.repeatCount).thenReturn(repeat)
        }

    @Test
    fun dpad_up_maps_to_previous() {
        val h = Harness()
        assertTrue(h.router.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN)))
        assertEquals(listOf<MenuCommand>(MenuCommand.Previous), h.commands)
    }

    @Test
    fun dpad_down_maps_to_next() {
        val h = Harness()
        h.router.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        assertEquals(listOf<MenuCommand>(MenuCommand.Next), h.commands)
    }

    @Test
    fun cross_activates_selected() {
        val h = Harness()
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        assertEquals(listOf<MenuCommand>(MenuCommand.Activate), h.commands)
    }

    @Test
    fun circle_is_back() {
        val h = Harness()
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        assertEquals(listOf<MenuCommand>(MenuCommand.Back), h.commands)
    }

    @Test
    fun square_and_triangle_are_page_actions() {
        val h = Harness()
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        assertEquals(listOf<MenuCommand>(MenuCommand.PageAction1, MenuCommand.PageAction2), h.commands)
    }

    @Test
    fun l1_r1_are_page_jumps() {
        val h = Harness()
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        h.router.handleKey(KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN))
        assertEquals(listOf<MenuCommand>(MenuCommand.PageUp, MenuCommand.PageDown), h.commands)
    }

    @Test
    fun auto_repeat_keys_are_consumed_without_new_command() {
        val h = Harness()
        assertTrue(h.router.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, keyEvent(KeyEvent.ACTION_DOWN, repeat = 1)))
        assertTrue(h.commands.isEmpty())
    }

    @Test
    fun stick_edge_fires_once_then_repeats_after_delay() {
        val h = Harness()
        val motion = Mockito.mock(MotionEvent::class.java)
        Mockito.`when`(motion.source).thenReturn(android.view.InputDevice.SOURCE_JOYSTICK)
        Mockito.`when`(motion.action).thenReturn(MotionEvent.ACTION_MOVE)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(-0.9f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_Y)).thenReturn(0f)

        // Initial edge: exactly one command
        assertTrue(h.router.handleMotion(motion))
        assertEquals(1, h.commands.size)

        // Held within delay: no new command emitted
        h.now += 100
        assertFalse(h.router.handleMotion(motion))
        assertEquals(1, h.commands.size)

        // After delay: repeat fires
        h.now += 250
        assertTrue(h.router.handleMotion(motion))
        assertEquals(2, h.commands.size)
        assertEquals(MenuCommand.Previous, h.commands.last())

        // Repeat interval cadence
        h.now += 50
        assertFalse(h.router.handleMotion(motion))
        h.now += 60
        assertTrue(h.router.handleMotion(motion))
        assertEquals(3, h.commands.size)
    }

    @Test
    fun stick_must_return_to_deadzone_before_new_edge() {
        val h = Harness()
        val motion = Mockito.mock(MotionEvent::class.java)
        Mockito.`when`(motion.source).thenReturn(android.view.InputDevice.SOURCE_JOYSTICK)
        Mockito.`when`(motion.action).thenReturn(MotionEvent.ACTION_MOVE)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(-0.9f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_Y)).thenReturn(0f)

        h.router.handleMotion(motion) // edge
        h.now += 1000
        h.router.handleMotion(motion) // repeat
        assertEquals(2, h.commands.size)

        // Return to neutral
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(0f)
        h.router.handleMotion(motion)
        // New edge immediately without waiting for old timers
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(-0.9f)
        assertTrue(h.router.handleMotion(motion))
        assertEquals(3, h.commands.size)
    }

    @Test
    fun cancel_repeat_blocks_pending_repeats() {
        val h = Harness()
        val motion = Mockito.mock(MotionEvent::class.java)
        Mockito.`when`(motion.source).thenReturn(android.view.InputDevice.SOURCE_JOYSTICK)
        Mockito.`when`(motion.action).thenReturn(MotionEvent.ACTION_MOVE)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(0.9f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_Y)).thenReturn(0f)

        h.router.handleMotion(motion) // edge (1)
        h.router.cancelRepeat()
        // After cancel, a still-deflected event re-edges exactly once — the
        // pending repeat cadence did not carry over.
        h.now += 10
        assertTrue(h.router.handleMotion(motion))
        assertEquals(2, h.commands.size)
        // Cadence restarted: no repeat within the fresh delay window
        h.now += 100
        assertFalse(h.router.handleMotion(motion))
        assertEquals(2, h.commands.size)
    }

    @Test
    fun deadzone_respected() {
        val h = Harness()
        val motion = Mockito.mock(MotionEvent::class.java)
        Mockito.`when`(motion.source).thenReturn(android.view.InputDevice.SOURCE_JOYSTICK)
        Mockito.`when`(motion.action).thenReturn(MotionEvent.ACTION_MOVE)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_Y)).thenReturn(0.3f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_X)).thenReturn(0f)
        Mockito.`when`(motion.getAxisValue(MotionEvent.AXIS_HAT_Y)).thenReturn(0f)
        assertFalse(h.router.handleMotion(motion))
        assertTrue(h.commands.isEmpty())
    }
}
