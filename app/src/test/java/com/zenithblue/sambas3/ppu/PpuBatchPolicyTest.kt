package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuBatchPolicyTest {

    @Test
    fun nextBatchSizeOnFailure_fallsBackStepwise() {
        assertEquals(8, PpuBatchPolicy.nextBatchSizeOnFailure(16))
        assertEquals(4, PpuBatchPolicy.nextBatchSizeOnFailure(8))
        assertEquals(2, PpuBatchPolicy.nextBatchSizeOnFailure(4))
        assertEquals(1, PpuBatchPolicy.nextBatchSizeOnFailure(2))
        assertEquals(1, PpuBatchPolicy.nextBatchSizeOnFailure(1))
    }

    @Test
    fun shouldFailPermanently_requiresMaxRetriesAtMinSize() {
        assertFalse(PpuBatchPolicy.shouldFailPermanently(0))
        assertFalse(PpuBatchPolicy.shouldFailPermanently(1))
        assertTrue(PpuBatchPolicy.shouldFailPermanently(2))
        assertTrue(PpuBatchPolicy.shouldFailPermanently(3))
    }
}
