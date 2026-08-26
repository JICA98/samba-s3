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
        READY,
        FAILED
    }
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
        // If game is placeholder or has install progress, it's importing
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
        // Use PpuReadinessStore — requires context
        val preRuntime = try { PpuReadinessStore.getPreRuntimeState(context, key) } catch (_: Exception) { PreRuntimePpuState.NOT_DONE }
        val runtime = try { PpuReadinessStore.getRuntimeState(context, key) } catch (_: Exception) { RuntimePpuState.NOT_STARTED }

        return when {
            preRuntime == PreRuntimePpuState.FAILED || runtime == RuntimePpuState.FAILED ->
                GameRunEligibility(false, GameRunEligibility.Status.FAILED)
            preRuntime == PreRuntimePpuState.READY && runtime == RuntimePpuState.IDLE_AFTER_COMPILE ->
                GameRunEligibility(true, GameRunEligibility.Status.READY)
            preRuntime == PreRuntimePpuState.IN_PROGRESS || runtime == RuntimePpuState.COMPILING ->
                GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
            else -> {
                // If no PPU state recorded, allow run for backward compat if game installed
                // But per plan, Run should be gated until READY+IDLE. For now, require READY.
                // If states are NOT_DONE/NOT_STARTED, we consider it needs preparation
                // To avoid blocking existing installs, treat unknown as READY for now?
                // Strict gating: only READY+IDLE canRun
                GameRunEligibility(false, GameRunEligibility.Status.PREPARING_PPU)
            }
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
