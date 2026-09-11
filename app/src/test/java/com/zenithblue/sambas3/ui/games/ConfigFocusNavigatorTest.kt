package com.zenithblue.sambas3.ui.games

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigFocusNavigatorTest {

    @Test
    fun move_fromNothingForward_landsOnFirst() {
        assertEquals(0, ConfigFocusNavigator.move(current = -1, delta = 1, count = 5))
    }

    @Test
    fun move_fromNothingBackward_landsOnLast() {
        assertEquals(4, ConfigFocusNavigator.move(current = -1, delta = -1, count = 5))
    }

    @Test
    fun move_fromFirstBackward_wrapsToLast() {
        assertEquals(4, ConfigFocusNavigator.move(current = 0, delta = -1, count = 5))
    }

    @Test
    fun move_fromLastForward_wrapsToFirst() {
        assertEquals(0, ConfigFocusNavigator.move(current = 4, delta = 1, count = 5))
    }

    @Test
    fun move_multiStepBackward_wraps() {
        assertEquals(3, ConfigFocusNavigator.move(current = 1, delta = -3, count = 5))
    }

    @Test
    fun move_emptyList_returnsMinusOne() {
        assertEquals(-1, ConfigFocusNavigator.move(current = 0, delta = 1, count = 0))
    }

    @Test
    fun sectionJump_forwardWraps() {
        assertEquals(0, ConfigFocusNavigator.sectionJump(current = 2, delta = 1, sectionCount = 3))
    }

    @Test
    fun sectionJump_backwardWraps() {
        assertEquals(2, ConfigFocusNavigator.sectionJump(current = 0, delta = -1, sectionCount = 3))
    }

    @Test
    fun sectionJump_clampsCurrentIntoRange() {
        assertEquals(2, ConfigFocusNavigator.sectionJump(current = 9, delta = 0, sectionCount = 3))
    }

    @Test
    fun sectionJump_noSections_returnsZero() {
        assertEquals(0, ConfigFocusNavigator.sectionJump(current = 4, delta = 1, sectionCount = 0))
    }
}
