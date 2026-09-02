package com.zenithblue.sambas3.ppu

/**
 * Pure first-frame validation state machine for fresh RPCSXActivity boots.
 * Runtime readiness is only established after a real Runtime PPU terminal
 * (when needed) plus stable PixelCopy samples on the current Surface generation.
 */
enum class FreshBootFramePhase {
    Idle,
    BootRequested,
    BootReturned,
    WaitingForRuntimePpu,
    WaitingForFirstFrame,
    Validated,
    Failed,
}

data class FreshBootFrameState(
    val phase: FreshBootFramePhase = FreshBootFramePhase.Idle,
    val surfaceGeneration: Long = -1L,
    val stableSamples: Int = 0,
    val requiredSamples: Int = 3,
    val runtimePpuSeen: Boolean = false,
    val failureReason: String? = null,
) {
    val isValidated: Boolean get() = phase == FreshBootFramePhase.Validated
    val isFailed: Boolean get() = phase == FreshBootFramePhase.Failed
}

object FreshBootFrameValidator {

    fun bootRequested(state: FreshBootFrameState = FreshBootFrameState()): FreshBootFrameState {
        if (state.phase == FreshBootFramePhase.Validated || state.phase == FreshBootFramePhase.Failed) {
            return state
        }
        return FreshBootFrameState(phase = FreshBootFramePhase.BootRequested)
    }

    fun bootReturned(
        state: FreshBootFrameState,
        noErrors: Boolean,
        surfaceGeneration: Long,
        runtimePpuActive: Boolean,
    ): FreshBootFrameState {
        if (state.phase != FreshBootFramePhase.BootRequested &&
            state.phase != FreshBootFramePhase.Idle
        ) {
            return state
        }
        if (!noErrors) {
            return state.copy(
                phase = FreshBootFramePhase.Failed,
                failureReason = "boot-result-error",
                surfaceGeneration = surfaceGeneration,
            )
        }
        return if (runtimePpuActive) {
            state.copy(
                phase = FreshBootFramePhase.WaitingForRuntimePpu,
                surfaceGeneration = surfaceGeneration,
                stableSamples = 0,
                runtimePpuSeen = true,
            )
        } else {
            state.copy(
                phase = FreshBootFramePhase.WaitingForFirstFrame,
                surfaceGeneration = surfaceGeneration,
                stableSamples = 0,
            )
        }
    }

    fun onRuntimePpuBegin(state: FreshBootFrameState, surfaceGeneration: Long): FreshBootFrameState {
        if (state.phase == FreshBootFramePhase.Validated || state.phase == FreshBootFramePhase.Failed) {
            return state
        }
        return state.copy(
            phase = FreshBootFramePhase.WaitingForRuntimePpu,
            surfaceGeneration = surfaceGeneration,
            stableSamples = 0,
            runtimePpuSeen = true,
        )
    }

    fun onRuntimePpuTerminal(state: FreshBootFrameState, surfaceGeneration: Long): FreshBootFrameState {
        if (state.phase != FreshBootFramePhase.WaitingForRuntimePpu &&
            state.phase != FreshBootFramePhase.BootReturned &&
            state.phase != FreshBootFramePhase.WaitingForFirstFrame
        ) {
            return state
        }
        return state.copy(
            phase = FreshBootFramePhase.WaitingForFirstFrame,
            surfaceGeneration = surfaceGeneration,
            stableSamples = 0,
        )
    }

    /**
     * @return updated state. Surface generation mismatch restarts the probe sequence.
     */
    fun onFrameProbe(
        state: FreshBootFrameState,
        copied: Boolean,
        running: Boolean,
        surfaceGeneration: Long,
    ): FreshBootFrameState {
        if (state.phase != FreshBootFramePhase.WaitingForFirstFrame) return state
        if (surfaceGeneration != state.surfaceGeneration) {
            return state.copy(
                surfaceGeneration = surfaceGeneration,
                stableSamples = 0,
            )
        }
        if (!running || !copied) {
            return state.copy(stableSamples = 0)
        }
        val next = state.stableSamples + 1
        return if (next >= state.requiredSamples) {
            state.copy(phase = FreshBootFramePhase.Validated, stableSamples = next)
        } else {
            state.copy(stableSamples = next)
        }
    }

    fun onTimeout(state: FreshBootFrameState, reason: String = "frame-timeout"): FreshBootFrameState {
        if (state.phase == FreshBootFramePhase.Validated) return state
        // Do not timeout while legitimate Runtime PPU is still active.
        if (state.phase == FreshBootFramePhase.WaitingForRuntimePpu) return state
        if (state.phase != FreshBootFramePhase.WaitingForFirstFrame &&
            state.phase != FreshBootFramePhase.BootReturned
        ) {
            return state
        }
        return state.copy(phase = FreshBootFramePhase.Failed, failureReason = reason)
    }
}
