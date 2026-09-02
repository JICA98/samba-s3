package com.zenithblue.sambas3.ui.games.launch

import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.ppu.GameLaunchAvailability

/** Live compile + emulator inputs for Launch Center snapshot mapping. */
data class LaunchRuntimeInputs(
    val installPpu: CompileProgressBridge.CompileState,
    val prelaunchPpu: CompileProgressBridge.CompileState,
    val runtimePpu: CompileProgressBridge.CompileState,
    val emulatorState: EmulatorState,
    val activeGame: String?,
    val waitingForIdle: Boolean = false,
    val deferredForFgs: Boolean = false,
    val fgsStartDenied: Boolean = false,
    val preRuntimeState: PreRuntimePpuState = PreRuntimePpuState.NOT_DONE,
    val runtimeReadyState: RuntimePpuState = RuntimePpuState.NOT_STARTED,
    val validatedByRealBootFrame: Boolean = false,
)

enum class PpuPhaseState {
    NotReady,
    Waiting,
    Preparing,
    Compiling,
    Finalizing,
    Ready,
    Failed,
    Deferred,
}

data class PpuPhaseUi(
    val label: String,
    val state: PpuPhaseState,
    val progress: Int?,
    val detail: String?,
)

enum class PrepareAction {
    /** Install phase needs re-import / rebuild — not headless Runtime. */
    ReimportOrRebuild,
    PreparingInstall,
    PreparingRuntime,
}

enum class PrimaryStartLabel {
    Start,
    StartAndPrepare,
    RetryOnStart,
}

data class LaunchPpuUi(
    val installPpu: PpuPhaseUi,
    val runtimePpu: PpuPhaseUi,
    val startEnabled: Boolean,
    val prepareAction: PrepareAction?,
    val primaryStartLabel: PrimaryStartLabel = PrimaryStartLabel.Start,
    val statusLine: String?,
)

/**
 * Pure presentation mapping — eligibility remains the authority for START.
 * Phase rows explain what is happening; they do not independently decide boot.
 */
object LaunchPpuPresentation {

    fun ownsTitle(stateTitleId: String?, currentTitleId: String?): Boolean {
        if (currentTitleId.isNullOrBlank()) return false
        if (stateTitleId.isNullOrBlank()) return false
        return stateTitleId.equals(currentTitleId, ignoreCase = true)
    }

    fun installActiveForTitle(install: CompileProgressBridge.CompileState, titleId: String?): Boolean {
        if (!install.ppuActive) return false
        // Unknown ownership → treat as busy/waiting rather than attributing foreign % to this title.
        if (install.titleId.isNullOrBlank()) return true
        return ownsTitle(install.titleId, titleId)
    }

    fun prelaunchActiveForTitle(prelaunch: CompileProgressBridge.CompileState, titleId: String?): Boolean {
        if (!prelaunch.ppuActive) return false
        if (prelaunch.titleId.isNullOrBlank()) return true
        return ownsTitle(prelaunch.titleId, titleId)
    }

    fun build(
        titleId: String?,
        availability: GameLaunchAvailability,
        inputs: LaunchRuntimeInputs,
    ): LaunchPpuUi {
        val installForThis = installActiveForTitle(inputs.installPpu, titleId)
        val foreignInstall = inputs.installPpu.ppuActive && !installForThis && !inputs.installPpu.titleId.isNullOrBlank()
        val prelaunchForThis = prelaunchActiveForTitle(inputs.prelaunchPpu, titleId)
        val foreignPrelaunch = inputs.prelaunchPpu.ppuActive && !prelaunchForThis && !inputs.prelaunchPpu.titleId.isNullOrBlank()

        val installFinal = when {
            installForThis -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Compiling,
                progress = inputs.installPpu.ppuPercent.coerceIn(0, 100),
                detail = inputs.installPpu.ppuMsg ?: "Compiling ${inputs.installPpu.ppuPercent}%"
            )
            foreignInstall -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = "Waiting"
            )
            inputs.preRuntimeState == PreRuntimePpuState.FAILED -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Failed,
                progress = null,
                detail = "Failed"
            )
            inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Ready,
                progress = null,
                detail = "Ready"
            )
            inputs.preRuntimeState == PreRuntimePpuState.IN_PROGRESS -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Preparing,
                progress = null,
                detail = "Preparing"
            )
            inputs.preRuntimeState == PreRuntimePpuState.INVALIDATED ||
                inputs.preRuntimeState == PreRuntimePpuState.NOT_DONE -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.NotReady,
                progress = null,
                detail = "Needs preparation"
            )
            else -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.NotReady,
                progress = null,
                detail = "Not ready"
            )
        }

        val runtimeActiveForThis = inputs.runtimePpu.ppuActive &&
            (inputs.runtimePpu.titleId.isNullOrBlank() || ownsTitle(inputs.runtimePpu.titleId, titleId))

        val runtimeUi = when {
            installForThis -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = "Waiting for install PPU"
            )
            (inputs.deferredForFgs || inputs.fgsStartDenied) &&
                (inputs.waitingForIdle || inputs.runtimeReadyState == RuntimePpuState.COMPILING) &&
                !prelaunchForThis && !runtimeActiveForThis ->
                PpuPhaseUi(
                    label = "Runtime PPU",
                    state = PpuPhaseState.Deferred,
                    progress = null,
                    detail = "Waiting to continue preparation"
                )
            prelaunchForThis || runtimeActiveForThis -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Compiling,
                progress = (if (runtimeActiveForThis) inputs.runtimePpu else inputs.prelaunchPpu)
                    .ppuPercent.coerceIn(0, 100),
                detail = (if (runtimeActiveForThis) inputs.runtimePpu.ppuMsg else inputs.prelaunchPpu.ppuMsg)
                    ?: "Compiling"
            )
            foreignPrelaunch -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = "Waiting"
            )
            inputs.waitingForIdle -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Preparing,
                progress = null,
                detail = "Preparing"
            )
            inputs.runtimeReadyState == RuntimePpuState.COMPILING -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Finalizing,
                progress = null,
                detail = "Finalizing"
            )
            inputs.runtimeReadyState == RuntimePpuState.FAILED &&
                inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Failed,
                progress = null,
                detail = "Retry on start"
            )
            inputs.validatedByRealBootFrame &&
                inputs.runtimeReadyState == RuntimePpuState.IDLE_AFTER_COMPILE &&
                inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Ready,
                progress = null,
                detail = "Ready"
            )
            inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.NotReady,
                progress = null,
                detail = "Will prepare on start"
            )
            else -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = "Waiting"
            )
        }

        val startEnabled = availability is GameLaunchAvailability.Ready

        val prepareAction: PrepareAction? = when {
            installForThis -> PrepareAction.PreparingInstall
            prelaunchForThis || runtimeActiveForThis || inputs.waitingForIdle ||
                (inputs.runtimeReadyState == RuntimePpuState.COMPILING) -> PrepareAction.PreparingRuntime
            availability is GameLaunchAvailability.Failed ||
                availability is GameLaunchAvailability.NeedsPreparation -> PrepareAction.ReimportOrRebuild
            else -> null
        }

        val primaryStartLabel = when {
            !startEnabled -> PrimaryStartLabel.Start
            inputs.validatedByRealBootFrame &&
                inputs.runtimeReadyState == RuntimePpuState.IDLE_AFTER_COMPILE ->
                PrimaryStartLabel.Start
            inputs.runtimeReadyState == RuntimePpuState.FAILED ->
                PrimaryStartLabel.RetryOnStart
            else -> PrimaryStartLabel.StartAndPrepare
        }

        val statusLine = when {
            startEnabled -> null
            availability is GameLaunchAvailability.Failed -> availability.reason ?: "Install PPU failed — re-import required"
            installForThis || prelaunchForThis || runtimeActiveForThis || inputs.waitingForIdle -> null
            availability is GameLaunchAvailability.NeedsPreparation -> "Re-import required"
            availability is GameLaunchAvailability.PreparingPpu -> "PPU not ready"
            availability is GameLaunchAvailability.EngineBusy -> "Emulator busy"
            availability is GameLaunchAvailability.Importing -> "Import still in progress"
            availability is GameLaunchAvailability.GameplayRunning -> "Game already running"
            else -> null
        }

        return LaunchPpuUi(
            installPpu = installFinal,
            runtimePpu = runtimeUi,
            startEnabled = startEnabled,
            prepareAction = prepareAction,
            primaryStartLabel = primaryStartLabel,
            statusLine = statusLine,
        )
    }

    fun compactEmptySaves(hasExistingSaves: Boolean): Boolean = !hasExistingSaves
}
