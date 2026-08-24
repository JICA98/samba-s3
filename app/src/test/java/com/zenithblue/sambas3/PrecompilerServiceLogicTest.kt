package com.zenithblue.sambas3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecompilerServiceLogicTest {

    @Test
    fun emptyStartDoesNotStopRunningJob() {
        assertFalse(PrecompilerServiceLogic.shouldStopEmptyStart(hasRunningJob = true))
        assertTrue(PrecompilerServiceLogic.shouldStopEmptyStart(hasRunningJob = false))
    }

    @Test
    fun extractUriRejectsBlankString() {
        assertNull(PrecompilerServiceLogic.extractUri(null, null, "  "))
        assertNull(PrecompilerServiceLogic.extractUri(null, null, null))
    }
}
