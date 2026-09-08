package com.zenithblue.sambas3.ppu

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState

/**
 * Phase-aware user action for Home / Launch Center.
 * Home and Launch Center run the required phase in isolated Kotlin-owned workers.
 */
enum class PpuUserAction {
    NONE,
    REBUILD_INSTALL_PPU,
    PREPARE_RUNTIME,
    RETRY_RUNTIME_PREPARATION,
    WAIT_FOR_ACTIVE_JOB,
    START,
}

data class PpuActionInputs(
    val preRuntime: PreRuntimePpuState,
    val runtime: RuntimePpuState,
    val validatedByRealBootFrame: Boolean,
    val installPpuActive: Boolean = false,
    val prelaunchPpuActive: Boolean = false,
    val runtimePpuActive: Boolean = false,
    val waitingForIdle: Boolean = false,
)

object PpuUserActionDecision {

    fun decide(inputs: PpuActionInputs): PpuUserAction {
        if (inputs.installPpuActive ||
            inputs.prelaunchPpuActive ||
            inputs.runtimePpuActive ||
            inputs.waitingForIdle
        ) {
            return PpuUserAction.WAIT_FOR_ACTIVE_JOB
        }

        return when (inputs.preRuntime) {
            PreRuntimePpuState.NOT_DONE,
            PreRuntimePpuState.INVALIDATED,
            PreRuntimePpuState.FAILED,
            // Persisted IN_PROGRESS with no live worker is a force-stop leftover.
            PreRuntimePpuState.IN_PROGRESS ->
                PpuUserAction.REBUILD_INSTALL_PPU

            PreRuntimePpuState.READY -> when {
                inputs.runtime == RuntimePpuState.IDLE_AFTER_COMPILE ->
                    PpuUserAction.START

                // Persisted COMPILING with no live worker is a force-stop leftover.
                inputs.runtime == RuntimePpuState.FAILED ||
                    inputs.runtime == RuntimePpuState.COMPILING ->
                    PpuUserAction.RETRY_RUNTIME_PREPARATION

                else -> PpuUserAction.PREPARE_RUNTIME
            }
        }
    }

    /** Gameplay opens only after both Kotlin-owned phases are terminal. */
    fun canEnterRealBoot(action: PpuUserAction): Boolean = action == PpuUserAction.START
}
