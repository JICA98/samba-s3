package com.zenithblue.sambas3.ppu

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState

/** Pure decision used by the activity while it holds an original boot request. */
internal enum class PpuBootContinuationState { WAITING, READY, FAILED, REPLACED }

internal object PpuBootContinuationPolicy {
    /**
     * Classifies the state immediately after a preparation request. This is
     * also used when a cached job finishes before the Activity can observe its
     * owner record.
     */
    fun resolvePreparationOutcome(
        expectedTitleId: String,
        activeOwner: ImportPpuPreparationCoordinator.ActiveOwnerSnapshot?,
        preRuntime: PreRuntimePpuState,
        runtime: RuntimePpuState,
    ): PpuBootContinuationState {
        if (activeOwner != null) {
            return if (activeOwner.titleId.equals(expectedTitleId, ignoreCase = true)) {
                PpuBootContinuationState.WAITING
            } else {
                PpuBootContinuationState.REPLACED
            }
        }

        return if (preRuntime == PreRuntimePpuState.READY &&
            runtime == RuntimePpuState.IDLE_AFTER_COMPILE
        ) PpuBootContinuationState.READY else PpuBootContinuationState.FAILED
    }

    fun shouldAdmitPreparationContinuation(availability: GameLaunchAvailability): Boolean = when (availability) {
        is GameLaunchAvailability.Failed -> availability.retryable
        GameLaunchAvailability.NeedsPreparation,
        is GameLaunchAvailability.PreparingPpu -> true
        else -> false
    }

    fun decide(
        expectedTitleId: String,
        expectedSessionId: Long,
        activeOwner: ImportPpuPreparationCoordinator.ActiveOwnerSnapshot?,
        currentSessionId: Long,
        preRuntime: PreRuntimePpuState,
        runtime: RuntimePpuState,
    ): PpuBootContinuationState {
        if (activeOwner != null) {
            return if (activeOwner.titleId.equals(expectedTitleId, ignoreCase = true) &&
                activeOwner.sessionId == expectedSessionId
            ) PpuBootContinuationState.WAITING else PpuBootContinuationState.REPLACED
        }

        if (currentSessionId != expectedSessionId) return PpuBootContinuationState.REPLACED
        return when {
            preRuntime == PreRuntimePpuState.READY && runtime == RuntimePpuState.IDLE_AFTER_COMPILE ->
                PpuBootContinuationState.READY
            preRuntime == PreRuntimePpuState.FAILED || runtime == RuntimePpuState.FAILED ->
                PpuBootContinuationState.FAILED
            // A continuation is accepted only after its owner was admitted. If
            // that owner has retired without READY, no worker remains to make
            // progress; stale COMPILING/NOT_STARTED state must terminate.
            else -> PpuBootContinuationState.FAILED
        }
    }
}
