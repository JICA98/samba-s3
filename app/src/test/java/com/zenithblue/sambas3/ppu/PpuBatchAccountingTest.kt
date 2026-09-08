package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuBatchAccountingTest {
    @Test
    fun aggregatesDistinctCacheHitsAcrossElfsPlusNewCommits() {
        val required = (1..33).map { "obj-$it" }.toSet()
        val acc = PpuBatchAccounting(required, budget = 16, sealed = true)
        val cached = (1..30).map { "obj-$it" }.toSet()
        acc.seed { it in cached }
        assertTrue(acc.claim("obj-31"))
        assertTrue(acc.claim("obj-32"))
        assertTrue(acc.claim("obj-33"))
        assertTrue(acc.committed("obj-31") { true })
        assertTrue(acc.committed("obj-32") { true })
        assertTrue(acc.committed("obj-33") { true })
        acc.audit { true }
        val r = acc.report()
        assertEquals(33L, r.total)
        assertEquals(30L, r.cachedBefore)
        assertEquals(33L, r.validatedAfter)
        assertEquals(3L, r.newlyCommitted)
        assertEquals(PpuBatchAccounting.Outcome.ALL_COMPLETE, r.outcome)
    }

    @Test
    fun firstNonzeroCacheHitIsNotRetainedAsTotal() {
        val required = (1..30).map { "a-$it" }.toSet() + (1..20).map { "b-$it" }.toSet()
        val acc = PpuBatchAccounting(required, budget = 8, sealed = true)
        val hits = (1..10).map { "a-$it" }.toSet() + (1..20).map { "b-$it" }.toSet()
        acc.seed { it in hits }
        assertEquals(30L, acc.report().cachedBefore)
        assertFalse(acc.report().cachedBefore == 10L)
    }

    @Test
    fun enqueueIsNotCommitAndFailedWriteIsNotProgress() {
        val acc = PpuBatchAccounting(setOf("a", "b"), budget = 2, sealed = true)
        acc.seed { false }
        assertTrue(acc.claim("a"))
        assertFalse(acc.committed("a") { false })
        val r = acc.report()
        assertEquals(PpuBatchAccounting.Outcome.FAILED, r.outcome)
        assertEquals(0L, r.newlyCommitted)
        assertEquals(1L, r.enqueued)
    }

    @Test
    fun unsealedOrUnauditedCannotBeAllComplete() {
        val acc = PpuBatchAccounting(setOf("a"), budget = 1, sealed = false)
        acc.seed { true }
        acc.audit { true }
        assertEquals(PpuBatchAccounting.Outcome.MORE_WORK, acc.report().outcome)

        val sealed = PpuBatchAccounting(setOf("a", "b"), budget = 1, sealed = true)
        sealed.seed { it == "a" }
        assertTrue(sealed.claim("b"))
        assertTrue(sealed.committed("b") { true })
        assertEquals(PpuBatchAccounting.Outcome.MORE_WORK, sealed.report().outcome)
        sealed.audit { true }
        assertEquals(PpuBatchAccounting.Outcome.ALL_COMPLETE, sealed.report().outcome)
    }

    @Test
    fun cancelAndDuplicateClaim() {
        val acc = PpuBatchAccounting(setOf("a", "b"), budget = 2, sealed = true)
        acc.seed { false }
        assertTrue(acc.claim("a"))
        assertFalse(acc.claim("a"))
        acc.cancel()
        assertFalse(acc.claim("b"))
        assertEquals(PpuBatchAccounting.Outcome.CANCELED, acc.report().outcome)
    }

    @Test
    fun budgetExhaustionLeavesMoreWork() {
        val acc = PpuBatchAccounting(setOf("a", "b", "c"), budget = 1, sealed = true)
        acc.seed { false }
        assertTrue(acc.claim("a"))
        assertFalse(acc.claim("b"))
        assertTrue(acc.committed("a") { true })
        acc.audit { it == "a" }
        val r = acc.report()
        assertEquals(PpuBatchAccounting.Outcome.MORE_WORK, r.outcome)
        assertEquals(1L, r.enqueued)
        assertEquals(1L, r.newlyCommitted)
        assertEquals(1L, r.validatedAfter)
    }
}
