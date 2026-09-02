package com.zenithblue.sambas3.ppu

import android.content.Context
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState

/**
 * Pure helper — no side effects, no Activity dependency.
 * Both card-click and hint Play must consult the same helper.
 */
data class GameRunEligibility(
    val canRun: Boolean,
    val status: Status
) {
    enum class Status {
        IMPORT_REQUIRED,
        IMPORTING,
        PREPARING_PPU,
        WAITING_FOR_ENGINE_IDLE,
        READY,
        FAILED,
        NEEDS_PREPARATION
    }
}

sealed interface GameLaunchAvailability {
    data object ImportRequired : GameLaunchAvailability
    data object Importing : GameLaunchAvailability
    data object WaitingForEngineIdle : GameLaunchAvailability
    data class PreparingPpu(val progress: com.zenithblue.sambas3.CompileProgressBridge.CompileState?) : GameLaunchAvailability
    data class Failed(val retryable: Boolean, val reason: String?) : GameLaunchAvailability
    data object Ready : GameLaunchAvailability
    data object GameplayRunning : GameLaunchAvailability
    data object NeedsPreparation : GameLaunchAvailability
    data class EngineBusy(val state: com.zenithblue.sambas3.EmulatorState, val activeGame: String?) : GameLaunchAvailability
}

object GameRunEligibilityHelper {

    fun evaluate(
        context: Context,
        game: Game?,
        hasPendingImport: Boolean,
        installPpuActive: Boolean,
        prelaunchActive: Boolean
    ): GameRunEligibility {
        if (game == null) {
            return GameRunEligibility(false, GameRunEligibility.Status.IMPORT_REQUIRED)
        }
        val hasInstallProgress = game.findProgress(com.zenithblue.sambas3.GameProgressType.Install) != null
        if (hasInstallProgress || hasPendingImport || installPpuActive) {
            return GameRunEligibility(false, GameRunEligibility.Status.IMPORTING)
        }
        if (prelaunchActive) {
            return GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
        }

        val key = try {
            GameIdentity.titleIdOrNull(game.info.path, game.info.name.value) ?: GameIdentity.key(game.info.path, game.info.name.value)
        } catch (_: Exception) {
            game.info.path
        }
        val preRuntime = try { PpuReadinessStore.getPreRuntimeState(context, key) } catch (_: Exception) { PreRuntimePpuState.NOT_DONE }
        val runtime = try { PpuReadinessStore.getRuntimeState(context, key) } catch (_: Exception) { RuntimePpuState.NOT_STARTED }
        val validated = try { PpuReadinessStore.isRuntimeValidated(context, key) } catch (_: Exception) { false }
        val action = PpuUserActionDecision.decide(
            PpuActionInputs(
                preRuntime = preRuntime,
                runtime = runtime,
                validatedByRealBootFrame = validated,
                installPpuActive = installPpuActive,
                prelaunchPpuActive = prelaunchActive,
            )
        )

        return when (action) {
            PpuUserAction.START,
            PpuUserAction.START_AND_PREPARE_RUNTIME,
            PpuUserAction.RETRY_RUNTIME_ON_REAL_BOOT ->
                GameRunEligibility(true, GameRunEligibility.Status.READY)
            PpuUserAction.WAIT_FOR_ACTIVE_JOB ->
                GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
            PpuUserAction.REIMPORT_OR_REBUILD_INSTALL_PPU -> when {
                preRuntime == PreRuntimePpuState.FAILED ->
                    GameRunEligibility(false, GameRunEligibility.Status.FAILED)
                else ->
                    GameRunEligibility(false, GameRunEligibility.Status.NEEDS_PREPARATION)
            }
            PpuUserAction.NONE ->
                GameRunEligibility(false, GameRunEligibility.Status.NEEDS_PREPARATION)
        }
    }

    fun evaluateAvailability(
        context: Context,
        game: Game?,
        installPpuActive: Boolean,
        prelaunchState: com.zenithblue.sambas3.CompileProgressBridge.CompileState?,
        runtimeState: com.zenithblue.sambas3.CompileProgressBridge.CompileState?,
        emulatorState: com.zenithblue.sambas3.EmulatorState,
        activeGame: String?
    ): GameLaunchAvailability {
        if (game == null) return GameLaunchAvailability.ImportRequired
        val hasInstallProgress = game.findProgress(com.zenithblue.sambas3.GameProgressType.Install) != null
        if (hasInstallProgress) return GameLaunchAvailability.Importing
        // Gameplay running takes precedence over compile
        if (activeGame != null && (emulatorState == com.zenithblue.sambas3.EmulatorState.Running || emulatorState == com.zenithblue.sambas3.EmulatorState.Paused)) {
            return GameLaunchAvailability.GameplayRunning
        }
        // Native state is authoritative: every non-terminal state occupies the
        // core, even when the mirrored active-game value is temporarily null.
        if (emulatorState != com.zenithblue.sambas3.EmulatorState.Stopped) {
            return GameLaunchAvailability.EngineBusy(emulatorState, activeGame)
        }
        // Install PPU active
        if (installPpuActive) return GameLaunchAvailability.PreparingPpu(null)
        // Prelaunch active for this title
        val key = try { GameIdentity.titleIdOrNull(game.info.path, game.info.name.value) ?: GameIdentity.key(game.info.path, game.info.name.value) } catch (_: Exception) { game.info.path }
        val isPrelaunchForThis = prelaunchState?.ppuActive == true && prelaunchState.titleId?.equals(key, ignoreCase = true) == true
        if (isPrelaunchForThis) return GameLaunchAvailability.PreparingPpu(prelaunchState)
        if (
            runtimeState?.ppuActive == true
        ) {
            return GameLaunchAvailability
                .PreparingPpu(runtimeState)
        }
        val pre = try { PpuReadinessStore.getPreRuntimeState(context, key) } catch (_: Exception) { PreRuntimePpuState.NOT_DONE }
        val rt = try { PpuReadinessStore.getRuntimeState(context, key) } catch (_: Exception) { RuntimePpuState.NOT_STARTED }
        val validated = try { PpuReadinessStore.isRuntimeValidated(context, key) } catch (_: Exception) { false }
        val action = PpuUserActionDecision.decide(
            PpuActionInputs(
                preRuntime = pre,
                runtime = rt,
                validatedByRealBootFrame = validated,
                installPpuActive = false,
                prelaunchPpuActive = false,
                runtimePpuActive = false,
            )
        )
        return when (action) {
            PpuUserAction.START,
            PpuUserAction.START_AND_PREPARE_RUNTIME,
            PpuUserAction.RETRY_RUNTIME_ON_REAL_BOOT -> GameLaunchAvailability.Ready
            PpuUserAction.WAIT_FOR_ACTIVE_JOB -> GameLaunchAvailability.PreparingPpu(prelaunchState)
            PpuUserAction.REIMPORT_OR_REBUILD_INSTALL_PPU -> when {
                pre == PreRuntimePpuState.FAILED ->
                    GameLaunchAvailability.Failed(true, "Install PPU failed — re-import required")
                else -> GameLaunchAvailability.NeedsPreparation
            }
            PpuUserAction.NONE -> GameLaunchAvailability.NeedsPreparation
        }
    }

    fun canRunSimple(
        context: Context,
        game: Game,
        installPpuActive: Boolean,
        prelaunchActive: Boolean
    ): Boolean {
        return evaluate(context, game, false, installPpuActive, prelaunchActive).canRun
    }
}
