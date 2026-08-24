package com.zenithblue.sambas3.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTouchPolicyTest {

    @Test
    fun gameplay_touches_proceed_to_button_handling() {
        assertTrue(OverlayTouchPolicy.shouldAcceptOverlayTouch(false))
    }

    @Test
    fun menu_mode_consumes_every_overlay_touch() {
        assertFalse(OverlayTouchPolicy.shouldAcceptOverlayTouch(true))
    }

    @Test
    fun outside_menu_mode_floating_sticks_are_handled_and_spawnable() {
        assertTrue(OverlayTouchPolicy.shouldHandleFloatingSticks(false))
        assertTrue(OverlayTouchPolicy.shouldSpawnFloatingStick(false))
    }

    @Test
    fun inside_menu_mode_floating_sticks_are_suppressed() {
        assertFalse(OverlayTouchPolicy.shouldHandleFloatingSticks(true))
        assertFalse(OverlayTouchPolicy.shouldSpawnFloatingStick(true))
    }

    @Test
    fun menu_dim_alpha_matches_plan_value() {
        assertEquals(0.35f, OverlayTouchPolicy.MENU_DIM_ALPHA)
    }
}
