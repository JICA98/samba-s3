package com.zenithblue.sambas3.ppu

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState

/**
 * Phase-aware user action for Home / Launch Center.
 * Normal production paths must never manufacture PreRuntime READY or start
 * headless Runtime PPU for the wrong phase.
 */
enum class PpuUserAction {
    NONE,
    REIMPORT_OR_REBUILD_INSTALL_PPU,
    START_AND_PREPARE_RUNTIME,
    RETRY_RUNTIME_ON_REAL_BOOT,
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
            PreRuntimePpuState.FAILED ->
                PpuUserAction.REIMPORT_OR_REBUILD_INSTALL_PPU

            PreRuntimePpuState.IN_PROGRESS ->
                PpuUserAction.WAIT_FOR_ACTIVE_JOB

            PreRuntimePpuState.READY -> when {
                inputs.validatedByRealBootFrame &&
                    inputs.runtime == RuntimePpuState.IDLE_AFTER_COMPILE ->
                    PpuUserAction.START

                inputs.runtime == RuntimePpuState.FAILED ->
                    PpuUserAction.RETRY_RUNTIME_ON_REAL_BOOT

                inputs.runtime == RuntimePpuState.COMPILING ->
                    PpuUserAction.WAIT_FOR_ACTIVE_JOB

                // NOT_STARTED, or legacy IDLE_AFTER_COMPILE without real-boot validation
                else -> PpuUserAction.START_AND_PREPARE_RUNTIME
            }
        }
    }

    /** True when the action may open RPCSXActivity for a real boot (may compile Runtime PPU). */
    fun canEnterRealBoot(action: PpuUserAction): Boolean = when (action) {
        PpuUserAction.START,
        PpuUserAction.START_AND_PREPARE_RUNTIME,
        PpuUserAction.RETRY_RUNTIME_ON_REAL_BOOT -> true
        else -> false
    }

    /** Normal Home/Launch/post-install actions must never invoke headless Runtime PPU. */
    fun allowsHeadlessRuntimePpu(action: PpuUserAction): Boolean = false
}
