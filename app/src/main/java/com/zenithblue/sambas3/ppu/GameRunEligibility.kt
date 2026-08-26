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

        return when {
            preRuntime == PreRuntimePpuState.FAILED || runtime == RuntimePpuState.FAILED ->
                GameRunEligibility(false, GameRunEligibility.Status.FAILED)
            preRuntime == PreRuntimePpuState.READY && runtime == RuntimePpuState.IDLE_AFTER_COMPILE ->
                GameRunEligibility(true, GameRunEligibility.Status.READY)
            preRuntime == PreRuntimePpuState.IN_PROGRESS || runtime == RuntimePpuState.COMPILING ->
                GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
            preRuntime == PreRuntimePpuState.NOT_DONE && runtime == RuntimePpuState.NOT_STARTED ->
                // Old installs or fresh candidate before import — needs preparation, not bricked
                GameRunEligibility(false, GameRunEligibility.Status.NEEDS_PREPARATION)
            else -> {
                GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
            }
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
        return when {
            pre == PreRuntimePpuState.FAILED || rt == RuntimePpuState.FAILED -> GameLaunchAvailability.Failed(true, "PPU preparation failed")
            pre == PreRuntimePpuState.READY && rt == RuntimePpuState.IDLE_AFTER_COMPILE -> GameLaunchAvailability.Ready
            pre == PreRuntimePpuState.IN_PROGRESS || rt == RuntimePpuState.COMPILING -> GameLaunchAvailability.PreparingPpu(prelaunchState)
            pre == PreRuntimePpuState.NOT_DONE && rt == RuntimePpuState.NOT_STARTED -> GameLaunchAvailability.NeedsPreparation
            else -> GameLaunchAvailability.PreparingPpu(prelaunchState)
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
