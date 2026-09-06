package com.zenithblue.sambas3.ui.games.launch

import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import com.zenithblue.sambas3.ppu.PpuRemainingTime

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
    val activeCompileTitleId: String? = null,
    val stoppingCompile: Boolean = false,
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
    val remainingLabel: String? = null,
)

enum class PrepareAction {
    Prepare,
    Retry,
    PreparingInstall,
    PreparingRuntime,
    Stop,
    Stopping,
    Locked,
}

enum class PrimaryStartLabel {
    Start,
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

    fun liveCompileOwnerTitleId(inputs: LaunchRuntimeInputs): String? {
        inputs.activeCompileTitleId?.takeIf { it.isNotBlank() }?.let { return it }
        if (inputs.installPpu.ppuActive) {
            inputs.installPpu.titleId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (inputs.prelaunchPpu.ppuActive) {
            inputs.prelaunchPpu.titleId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (inputs.runtimePpu.ppuActive) {
            inputs.runtimePpu.titleId?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
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
        val prelaunchForThis = prelaunchActiveForTitle(inputs.prelaunchPpu, titleId)
        val foreignPrelaunch = inputs.prelaunchPpu.ppuActive && !prelaunchForThis && !inputs.prelaunchPpu.titleId.isNullOrBlank()
        val runtimeActiveForThis = inputs.runtimePpu.ppuActive &&
            (inputs.runtimePpu.titleId.isNullOrBlank() || ownsTitle(inputs.runtimePpu.titleId, titleId))
        val ownerTitleId = liveCompileOwnerTitleId(inputs)
        val thisOwnsLive = ownsTitle(ownerTitleId, titleId)
        val anyLiveCompile = inputs.installPpu.ppuActive ||
            inputs.prelaunchPpu.ppuActive ||
            inputs.runtimePpu.ppuActive ||
            inputs.waitingForIdle ||
            inputs.stoppingCompile ||
            !ownerTitleId.isNullOrBlank()
        val thisCompiling = installForThis || prelaunchForThis || runtimeActiveForThis ||
            (thisOwnsLive && anyLiveCompile)
        val foreignBusy = anyLiveCompile && !thisCompiling
        val foreignWaitDetail = "Waiting — another game is compiling"

        val installFinal = when {
            installForThis -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Compiling,
                progress = compileProgressPercent(inputs.installPpu),
                detail = compileProgressDetail(inputs.installPpu),
                remainingLabel = inputs.installPpu.remainingLabel,
            )
            foreignBusy && inputs.preRuntimeState != PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = foreignWaitDetail
            )
            inputs.preRuntimeState == PreRuntimePpuState.FAILED ||
                inputs.preRuntimeState == PreRuntimePpuState.IN_PROGRESS -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Failed,
                progress = null,
                detail = if (inputs.preRuntimeState == PreRuntimePpuState.IN_PROGRESS) {
                    "Interrupted — retry to resume"
                } else {
                    "Failed"
                }
            )
            inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Install PPU",
                state = PpuPhaseState.Ready,
                progress = null,
                detail = "Ready"
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
            prelaunchForThis || runtimeActiveForThis -> {
                val compiling = if (runtimeActiveForThis) inputs.runtimePpu else inputs.prelaunchPpu
                PpuPhaseUi(
                    label = "Runtime PPU",
                    state = PpuPhaseState.Compiling,
                    progress = compileProgressPercent(compiling),
                    detail = compileProgressDetail(compiling),
                    remainingLabel = compiling.remainingLabel,
                )
            }
            foreignPrelaunch || foreignBusy -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = foreignWaitDetail
            )
            inputs.waitingForIdle -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Preparing,
                progress = null,
                detail = "Preparing"
            )
            (inputs.runtimeReadyState == RuntimePpuState.FAILED ||
                inputs.runtimeReadyState == RuntimePpuState.COMPILING) &&
                inputs.preRuntimeState == PreRuntimePpuState.READY -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Failed,
                progress = null,
                detail = if (inputs.runtimeReadyState == RuntimePpuState.COMPILING) {
                    "Interrupted — retry to resume"
                } else {
                    "Preparation failed"
                }
            )
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
                detail = "Needs preparation"
            )
            else -> PpuPhaseUi(
                label = "Runtime PPU",
                state = PpuPhaseState.Waiting,
                progress = null,
                detail = "Waiting"
            )
        }

        val startEnabled = availability is GameLaunchAvailability.Ready &&
            !foreignBusy &&
            !thisCompiling &&
            !inputs.stoppingCompile

        val prepareAction: PrepareAction? = when {
            inputs.stoppingCompile && (thisCompiling || thisOwnsLive) -> PrepareAction.Stopping
            thisCompiling -> PrepareAction.Stop
            foreignBusy -> PrepareAction.Locked
            availability is GameLaunchAvailability.Failed -> PrepareAction.Retry
            availability is GameLaunchAvailability.NeedsPreparation -> PrepareAction.Prepare
            else -> null
        }

        val primaryStartLabel = PrimaryStartLabel.Start

        val statusLine = when {
            startEnabled -> null
            inputs.stoppingCompile && (thisCompiling || thisOwnsLive) -> "Stopping PPU compilation"
            thisCompiling -> null
            foreignBusy -> "Another game is compiling PPU"
            availability is GameLaunchAvailability.Failed -> availability.reason ?: "PPU preparation failed — retry"
            availability is GameLaunchAvailability.NeedsPreparation -> "PPU preparation required"
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

    fun compileProgressPercent(state: CompileProgressBridge.CompileState): Int? {
        if (state.moduleTotal > 0) {
            return (state.moduleDone * 100 / state.moduleTotal).coerceIn(0, 100)
        }
        return state.ppuPercent.takeIf { it > 0 }
    }

    fun compileBarFraction(state: CompileProgressBridge.CompileState): Float? {
        if (state.moduleTotal > 0) {
            return (state.moduleDone.toFloat() / state.moduleTotal.toFloat()).coerceIn(0f, 1f)
        }
        return state.ppuPercent.takeIf { it > 0 }?.div(100f)
    }

    fun compileProgressDetail(state: CompileProgressBridge.CompileState): String {
        state.ppuMsg?.takeIf { it.isNotBlank() }?.let { return it }
        val pct = compileProgressPercent(state)
        return if (pct != null) "Compiling ${pct}%" else "Compiling"
    }

    fun compileProgressLine(state: CompileProgressBridge.CompileState): String =
        PpuRemainingTime.progressLine(compileProgressDetail(state), state.remainingLabel)

    fun phaseStatusText(phase: PpuPhaseUi): String = when {
        phase.state == PpuPhaseState.Compiling && !phase.detail.isNullOrBlank() ->
            phase.detail
        phase.state == PpuPhaseState.Compiling && phase.progress != null && phase.progress > 0 ->
            "Compiling  ${phase.progress}%"
        !phase.detail.isNullOrBlank() -> phase.detail
        else -> phase.state.name
    }

    fun phaseStatusLine(phase: PpuPhaseUi): String =
        PpuRemainingTime.progressLine(phaseStatusText(phase), phase.remainingLabel)

    fun homeCardShowsCompileOverlay(
        isImporting: Boolean,
        isRuntimeGameCompile: Boolean,
        usingPrelaunchPpu: Boolean,
        usingInstallPpu: Boolean,
    ): Boolean = isImporting || isRuntimeGameCompile || usingPrelaunchPpu || usingInstallPpu

    fun compactEmptySaves(hasExistingSaves: Boolean): Boolean = !hasExistingSaves
}
