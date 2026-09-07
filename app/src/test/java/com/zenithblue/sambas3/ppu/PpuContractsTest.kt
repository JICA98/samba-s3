package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuContractsTest {
    private fun key() = AttemptKey("BLUS30109", "session-a", "attempt-1", "manifest-a", PpuKind.INSTALL)
    private fun worker(k: AttemptKey = key(), batch: Int = 0, inst: String = "w1") =
        WorkerKey(k, batch, inst)

    private fun snap(
        w: WorkerKey,
        seq: Long,
        validated: Long,
        committed: Long,
        required: Long? = 526,
        discovery: Boolean = required != null,
    ) = PpuSnapshot(w, seq, discovery, required, validated, committed)

    @Test
    fun attachRejectsWrongAttemptAndDuplicateWorker() {
        val k = key()
        val c = PreparationContract(k)
        assertTrue(c.attach(worker(k)))
        assertFalse(c.attach(worker(k, batch = 1)))
        val other = AttemptKey("BLUS30109", "session-a", "attempt-2", "manifest-a", PpuKind.INSTALL)
        val c2 = PreparationContract(k)
        assertFalse(c2.attach(worker(other)))
    }

    @Test
    fun progressRejectsStaleSequenceAndStop() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        assertTrue(c.attach(w))
        assertEquals(PpuAcceptance.ACCEPTED, c.progress(snap(w, 1, 10, 2)))
        assertEquals(PpuAcceptance.STALE, c.progress(snap(w, 1, 11, 3)))
        assertTrue(c.requestStop(k))
        assertEquals(PpuAcceptance.STALE, c.progress(snap(w, 2, 12, 4)))
    }

    @Test
    fun unknownTotalIsDiscoveringNotFabricatedDenominator() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        assertEquals(PpuAcceptance.ACCEPTED, c.progress(snap(w, 1, 192, 16, required = null, discovery = false)))
        val view = c.view()
        assertEquals(PpuStage.DISCOVERING, view.stage)
        assertNull(view.percent)
        assertTrue(view.text.contains("192"))
        assertTrue(view.text.contains("discovering") || view.text.contains("Discovering"))
    }

    @Test
    fun doneEqualsTotalIsVerifyingNotInventedModule() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        assertEquals(PpuAcceptance.ACCEPTED, c.progress(snap(w, 1, 526, 50, required = 526)))
        val view = c.view()
        assertEquals(PpuStage.VERIFYING, view.stage)
        assertNull(view.percent)
        assertTrue(view.text.contains("526 of 526"))
        assertFalse(view.text.contains("527"))
    }

    @Test
    fun allCompleteWithoutAuditIsInvalid() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        val s = snap(w, 1, 526, 50, 526)
        val r = PpuBatchResult(s, PpuBatchOutcome.ALL_COMPLETE, auditComplete = false)
        assertEquals(PpuAcceptance.INVALID, c.result(r))
        assertEquals(PpuStage.FAILED, c.stage)
        assertFalse(c.view().retryAllowed)
    }

    @Test
    fun readyOnlyAfterVerifiedExit() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        val s = snap(w, 1, 526, 50, 526)
        assertEquals(PpuAcceptance.ACCEPTED, c.result(PpuBatchResult(s, PpuBatchOutcome.ALL_COMPLETE, true)))
        assertEquals(PpuStage.VERIFYING, c.stage)
        assertTrue(c.observedWorkerExit(w))
        assertEquals(PpuStage.READY, c.stage)
        assertEquals(100, c.view().percent)
        assertFalse(c.view().retryAllowed)
    }

    @Test
    fun stopThenExitIsStoppedAndRetryAllowed() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        c.progress(snap(w, 1, 52, 16))
        assertTrue(c.requestStop(k))
        assertEquals(PpuStage.STOPPING, c.stage)
        assertFalse(c.view().retryAllowed)
        assertTrue(c.observedWorkerExit(w))
        assertEquals(PpuStage.STOPPED, c.stage)
        assertTrue(c.view().retryAllowed)
        assertFalse(c.requestStop(k))
    }

    @Test
    fun staleStopOnDifferentAttemptIsIgnored() {
        val k = key()
        val other = k.copy(attemptId = "attempt-other")
        val c = PreparationContract(k)
        assertFalse(c.requestStop(other))
        assertEquals(PpuStage.DISCOVERING, c.stage)
    }

    @Test
    fun sealedInventoryChangeIsInvalid() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        c.progress(snap(w, 1, 10, 1, 100))
        assertEquals(PpuAcceptance.INVALID, c.progress(snap(w, 2, 10, 1, 200)))
    }

    @Test
    fun oldWorkerExitDoesNotMutate() {
        val k = key()
        val w = worker(k)
        val c = PreparationContract(k)
        c.attach(w)
        assertFalse(c.observedWorkerExit(worker(k, inst = "other")))
        assertEquals(PpuStage.DISCOVERING, c.stage)
    }

    @Test
    fun noProgressGuardTripsAfterThreeEmptyBatches() {
        val g = NoProgressGuard(3)
        assertFalse(g.completedBatch(0, false))
        assertFalse(g.completedBatch(0, false))
        assertTrue(g.completedBatch(0, false))
        val g2 = NoProgressGuard(3)
        assertFalse(g2.completedBatch(0, false))
        assertFalse(g2.completedBatch(4, false))
        assertFalse(g2.completedBatch(0, true))
        assertFalse(g2.completedBatch(0, false))
    }

    @Test
    fun evidenceEtaIgnoresUnchangedSamplesAndStaleClock() {
        val eta = EvidenceEta(staleAfterMs = 60_000)
        val k = key()
        assertNull(eta.estimateMs(k, 100, 2, 2, PpuStage.COMPILING, 10_000))
        eta.reset()
        assertNull(eta.estimateMs(k, 100, 0, 0, PpuStage.COMPILING, 0))
        val remaining = eta.estimateMs(k, 100, 2, 2, PpuStage.COMPILING, 10_000)
        assertEquals(490_000L, remaining)
        val unchanged = eta.estimateMs(k, 100, 2, 2, PpuStage.COMPILING, 70_000)
        assertNull(unchanged)
    }

    @Test
    fun ownerRegistryRetainsLeaseUntilRetireEvenIfJobWouldBeInactive() {
        val registry = PpuOwnerRegistry()
        val k = key()
        val owner = PpuOwnerRegistry.Owner(
            key = k,
            sessionNumeric = 1L,
            titleId = k.titleId,
            compilePath = "/game",
            contract = PreparationContract(k),
            createdAtMs = 0L,
        )
        assertNotNull(registry.admit(owner))
        val k2 = k.copy(attemptId = "attempt-2")
        val owner2 = PpuOwnerRegistry.Owner(
            key = k2,
            sessionNumeric = 2L,
            titleId = k2.titleId,
            compilePath = "/game",
            contract = PreparationContract(k2),
            createdAtMs = 1L,
        )
        assertNull(registry.admit(owner2))
        registry.retire(owner)
        assertNotNull(registry.admit(owner2))
        assertTrue(registry.stillOwner(owner2))
    }

    @Test
    fun photographedNPlusOneShapesAreNotProducedByPresentation() {
        val v1 = PpuProgressPresentation.view(PpuStage.VERIFYING, 192, 192, true)
        assertFalse(v1.text.contains("193"))
        val v2 = PpuProgressPresentation.view(PpuStage.VERIFYING, 208, 208, true)
        assertFalse(v2.text.contains("209"))
        val compiling = PpuProgressPresentation.view(PpuStage.COMPILING, 51, 526, true)
        assertEquals(9, compiling.percent)
        assertTrue(compiling.text.contains("51 of 526"))
    }
}
