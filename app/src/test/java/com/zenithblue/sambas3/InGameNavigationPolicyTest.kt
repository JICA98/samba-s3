package com.zenithblue.sambas3

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class InGameNavigationPolicyTest {
    @Test
    fun running_or_paused_back_opens_menu() {
        assertEquals(
            AndroidBackAction.OpenMenu,
            resolveAndroidBackAction(false, false, EmulatorState.Running)
        )
        assertEquals(
            AndroidBackAction.OpenMenu,
            resolveAndroidBackAction(false, false, EmulatorState.Paused)
        )
    }

    @Test
    fun open_menu_back_is_delegated_to_page_navigation() {
        assertEquals(
            AndroidBackAction.DispatchMenuBack,
            resolveAndroidBackAction(false, true, EmulatorState.Running)
        )
    }

    @Test
    fun recovery_back_is_consumed_before_any_navigation() {
        assertEquals(
            AndroidBackAction.Consume,
            resolveAndroidBackAction(true, false, EmulatorState.Running)
        )
    }

    @Test
    fun stopped_back_finishes_and_transitional_states_are_consumed() {
        assertEquals(
            AndroidBackAction.FinishActivity,
            resolveAndroidBackAction(false, false, EmulatorState.Stopped)
        )
        assertEquals(
            AndroidBackAction.Consume,
            resolveAndroidBackAction(false, false, EmulatorState.Starting)
        )
    }

    @Test
    fun physical_home_is_one_action_per_press_and_repeat_is_ignored() {
        val gate = FrontendHomeKeyGate()

        assertEquals(true, gate.acceptDown(0))
        assertEquals(false, gate.acceptDown(0))
        assertEquals(false, gate.acceptDown(1))
        assertEquals(true, gate.acceptUp())
        assertEquals(true, gate.acceptDown(0))
    }

    @Test
    fun keyboard_renderer_shortcuts_are_reserved_for_game_chrome() {
        assertEquals(KeyboardRenderAction.PsButton, resolveKeyboardRenderAction(KeyEvent.KEYCODE_HOME))
        assertEquals(KeyboardRenderAction.HomeButton, resolveKeyboardRenderAction(KeyEvent.KEYCODE_F1))
        assertEquals(KeyboardRenderAction.KeyboardButton, resolveKeyboardRenderAction(KeyEvent.KEYCODE_F2))
        assertEquals(null, resolveKeyboardRenderAction(KeyEvent.KEYCODE_F3))
    }
}
