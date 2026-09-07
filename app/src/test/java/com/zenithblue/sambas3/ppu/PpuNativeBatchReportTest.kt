package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuNativeBatchReportTest {
    @Test
    fun parseAllCompleteRequiresSealedAudit() {
        val json = """{"status":"all_complete","totalModules":526,"cachedBefore":510,"cachedAfter":526,"compiledThisBatch":16,"enqueued":16,"remainingUncached":0,"inventorySealed":true,"audited":true}"""
        val r = PpuNativeBatchReport.parse(json)
        assertEquals(PpuBatchOutcome.ALL_COMPLETE, r.outcome)
        assertTrue(r.inventorySealed)
        assertTrue(r.audited)
        assertTrue(r.provesCompletion)
        assertEquals(16, r.compiledThisBatch)
    }

    @Test
    fun parseMoreWorkWhenRemaining() {
        val json = """{"status":"more_work","totalModules":526,"cachedBefore":10,"cachedAfter":26,"compiledThisBatch":16,"enqueued":16,"remainingUncached":500}"""
        val r = PpuNativeBatchReport.parse(json)
        assertEquals(PpuBatchOutcome.MORE_WORK, r.outcome)
        assertFalse(r.inventorySealed)
        assertEquals(500, r.remainingUncached)
    }

    @Test
    fun malformedJsonIsFailed() {
        val r = PpuNativeBatchReport.parse("{")
        assertEquals(PpuBatchOutcome.FAILED, r.outcome)
    }

    @Test
    fun legacyAllCompleteWithoutReceiptDoesNotProveCompletion() {
        val r = PpuNativeBatchReport.parse(
            """{"status":"all_complete","totalModules":10,"cachedAfter":10}""",
        )
        assertFalse(r.provesCompletion)
    }

    @Test
    fun inconsistentCompleteCountsDoNotProveCompletion() {
        val r = PpuNativeBatchReport.parse(
            """{"status":"all_complete","totalModules":10,"cachedAfter":9,"compiledThisBatch":2,"enqueued":2,"remainingUncached":0,"inventorySealed":true,"audited":true}""",
        )
        assertFalse(r.provesCompletion)
    }
}
