package com.zenithblue.sambas3.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingConflictResolverTest {

    private val base = mapOf(
        LogicalControl.CROSS to KeyEvent.KEYCODE_BUTTON_A,
        LogicalControl.CIRCLE to KeyEvent.KEYCODE_BUTTON_B,
        LogicalControl.SQUARE to KeyEvent.KEYCODE_BUTTON_X,
    )

    @Test
    fun findConflictDetectsExistingOwner() {
        val conflict = MappingConflictResolver.findConflict(base, LogicalControl.TRIANGLE, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(LogicalControl.CROSS, conflict.existing)
    }

    @Test
    fun replaceClearsPreviousOwner() {
        val result = MappingConflictResolver.apply(
            base,
            LogicalControl.TRIANGLE,
            KeyEvent.KEYCODE_BUTTON_A,
            RemapConflictAction.REPLACE,
        )
        assertTrue(result.applied)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, result.bindings[LogicalControl.TRIANGLE])
        assertFalse(result.bindings.containsKey(LogicalControl.CROSS))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, result.bindings[LogicalControl.CIRCLE])
    }

    @Test
    fun swapExchangesBindings() {
        val result = MappingConflictResolver.apply(
            base,
            LogicalControl.CROSS,
            KeyEvent.KEYCODE_BUTTON_B,
            RemapConflictAction.SWAP,
        )
        assertTrue(result.applied)
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, result.bindings[LogicalControl.CROSS])
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, result.bindings[LogicalControl.CIRCLE])
    }

    @Test
    fun cancelLeavesBindingsUntouched() {
        val result = MappingConflictResolver.apply(
            base,
            LogicalControl.TRIANGLE,
            KeyEvent.KEYCODE_BUTTON_A,
            RemapConflictAction.CANCEL,
        )
        assertFalse(result.applied)
        assertEquals(base, result.bindings)
    }

    @Test
    fun reservedPsHomeRejected() {
        val result = MappingConflictResolver.apply(
            base,
            LogicalControl.PS_HOME_FRONTEND,
            KeyEvent.KEYCODE_BUTTON_MODE,
            RemapConflictAction.REPLACE,
        )
        assertFalse(result.applied)
        assertEquals(base, result.bindings)
    }

    @Test
    fun noConflictWhenKeyUnassigned() {
        val conflict = MappingConflictResolver.findConflict(base, LogicalControl.TRIANGLE, KeyEvent.KEYCODE_BUTTON_Y)
        assertNull(conflict.existing)
        val result = MappingConflictResolver.apply(base, LogicalControl.TRIANGLE, KeyEvent.KEYCODE_BUTTON_Y, RemapConflictAction.REPLACE)
        assertTrue(result.applied)
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, result.bindings[LogicalControl.TRIANGLE])
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, result.bindings[LogicalControl.CROSS])
    }
}
