package com.zenithblue.sambas3.ppu

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import org.junit.Assert.assertEquals
import org.junit.Test

class PpuBootContinuationPolicyTest {
    private fun decide(
        ownerActive: Boolean = false,
        activeTitleId: String? = null,
        currentSessionId: Long = 41L,
        pre: PreRuntimePpuState = PreRuntimePpuState.READY,
        runtime: RuntimePpuState = RuntimePpuState.COMPILING,
    ) = PpuBootContinuationPolicy.decide(
        expectedTitleId = "BCUS98111",
        expectedSessionId = 41L,
        activeOwner = if (ownerActive) {
            activeTitleId?.let { ImportPpuPreparationCoordinator.ActiveOwnerSnapshot(it, currentSessionId) }
        } else null,
        currentSessionId = currentSessionId,
        preRuntime = pre,
        runtime = runtime,
    )

    @Test fun matchingNewPreparationWaitsUntilOwnerRetires() {
        assertEquals(
            PpuBootContinuationState.WAITING,
            decide(ownerActive = true, activeTitleId = "bcus98111", runtime = RuntimePpuState.IDLE_AFTER_COMPILE),
        )
    }

    @Test fun completedCachedPreparationAllowsExactlyOneContinuationDecision() {
        assertEquals(
            PpuBootContinuationState.READY,
            decide(runtime = RuntimePpuState.IDLE_AFTER_COMPILE),
        )
    }

    @Test fun priorFailureDoesNotTerminateWhileMatchingOwnerIsActive() {
        assertEquals(
            PpuBootContinuationState.WAITING,
            decide(ownerActive = true, activeTitleId = "BCUS98111", runtime = RuntimePpuState.FAILED),
        )
    }

    @Test fun failureIsTerminalOnlyAfterAcceptedSessionRetires() {
        assertEquals(
            PpuBootContinuationState.FAILED,
            decide(runtime = RuntimePpuState.FAILED),
        )
    }

    @Test fun anotherTitleOrReplacementSessionCannotConsumePendingBoot() {
        assertEquals(
            PpuBootContinuationState.REPLACED,
            decide(ownerActive = true, activeTitleId = "BCUS98111", currentSessionId = 42L),
        )
        assertEquals(
            PpuBootContinuationState.REPLACED,
            decide(ownerActive = true, activeTitleId = "BCUS98112"),
        )
    }

    @Test fun retiredNonterminalSessionFailsInsteadOfWaitingForever() {
        assertEquals(
            PpuBootContinuationState.FAILED,
            decide(pre = PreRuntimePpuState.READY, runtime = RuntimePpuState.COMPILING),
        )
        assertEquals(
            PpuBootContinuationState.FAILED,
            decide(pre = PreRuntimePpuState.READY, runtime = RuntimePpuState.NOT_STARTED),
        )
    }

    @Test fun readyRequestCanOnlyBeConsumedOnce() {
        val delivery = PpuBootContinuationDelivery()
        assertEquals(true, delivery.consume())
        assertEquals(false, delivery.consume())
    }

    private fun resolvePreparationOutcome(
        ownerTitleId: String? = null,
        pre: PreRuntimePpuState = PreRuntimePpuState.READY,
        runtime: RuntimePpuState = RuntimePpuState.IDLE_AFTER_COMPILE,
    ) = PpuBootContinuationPolicy.resolvePreparationOutcome(
        expectedTitleId = "BCUS98111",
        activeOwner = ownerTitleId?.let {
            ImportPpuPreparationCoordinator.ActiveOwnerSnapshot(it, 42L)
        },
        preRuntime = pre,
        runtime = runtime,
    )

    @Test fun initialPreparationWaitsOnlyForMatchingTitleOwner() {
        assertEquals(
            PpuBootContinuationState.WAITING,
            resolvePreparationOutcome(ownerTitleId = "bcus98111", runtime = RuntimePpuState.COMPILING),
        )
        assertEquals(
            PpuBootContinuationState.REPLACED,
            resolvePreparationOutcome(ownerTitleId = "BCUS99999"),
        )
    }

    @Test fun cachedPreparationThatRetiresBeforeSnapshotCanProceedWhenCanonicalReady() {
        assertEquals(PpuBootContinuationState.READY, resolvePreparationOutcome())
        assertEquals(
            PpuBootContinuationState.FAILED,
            resolvePreparationOutcome(runtime = RuntimePpuState.COMPILING),
        )
    }

    @Test fun onlyRetryableFailuresCanAcquireContinuation() {
        assertEquals(
            true,
            PpuBootContinuationPolicy.shouldAdmitPreparationContinuation(
                GameLaunchAvailability.Failed(retryable = true, reason = "retry"),
            ),
        )
        assertEquals(
            false,
            PpuBootContinuationPolicy.shouldAdmitPreparationContinuation(
                GameLaunchAvailability.Failed(retryable = false, reason = "permanent"),
            ),
        )
        assertEquals(
            true,
            PpuBootContinuationPolicy.shouldAdmitPreparationContinuation(GameLaunchAvailability.NeedsPreparation),
        )
        assertEquals(
            true,
            PpuBootContinuationPolicy.shouldAdmitPreparationContinuation(
                GameLaunchAvailability.PreparingPpu(null),
            ),
        )
    }
}
