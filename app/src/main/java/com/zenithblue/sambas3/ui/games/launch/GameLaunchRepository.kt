package com.zenithblue.sambas3.ui.games.launch

import android.content.Context
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import com.zenithblue.sambas3.ppu.GameRunEligibilityHelper
import com.zenithblue.sambas3.ui.ingame.SaveSlot
import com.zenithblue.sambas3.utils.GeneralSettings

object GameLaunchRepository {
    fun snapshot(context: Context, game: Game): GameLaunchSnapshot {
        val titleId = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
        val overrides = titleId?.let { GameSettingsOverrides.gameOverrides(context, it) }.orEmpty()
        val globals = GameSettingsOverrides.resolvedGlobalValues(context)
        fun setting(label: String, path: String, fallback: String): LaunchSetting {
            val value = overrides[path] ?: globals[path] ?: fallback
            return LaunchSetting(label, value, if (path in overrides) "GAME" else "GLOBAL")
        }
        val availability = GameRunEligibilityHelper.evaluateAvailability(
            context, game,
            false, null, null,
            RPCSX.state.value, RPCSX.activeGame.value
        )
        val slots = GameSavestateRepository.slots(context, game)
        val latest = slots.filter { it.exists }.maxByOrNull { it.mtimeMs }
        val active = RPCSX.activeGame.value
        val sameRunning = active == game.info.path &&
            (RPCSX.state.value == EmulatorState.Running || RPCSX.state.value == EmulatorState.Paused)
        val otherRunning = active != null && active != game.info.path &&
            (RPCSX.state.value == EmulatorState.Running || RPCSX.state.value == EmulatorState.Paused)
        val blocked = when {
            otherRunning -> "Another game is already running"
            availability is GameLaunchAvailability.GameplayRunning && !sameRunning -> "An emulator session is already running"
            availability is GameLaunchAvailability.PreparingPpu -> "Preparing PPU"
            availability is GameLaunchAvailability.Importing -> "Import still in progress"
            availability is GameLaunchAvailability.NeedsPreparation -> "PPU preparation required"
            availability is GameLaunchAvailability.Failed -> availability.reason
            else -> null
        }
        return GameLaunchSnapshot(
            game = game,
            titleId = titleId,
            selectedDriver = (GeneralSettings["selected_gpu_driver"] as? String)?.ifBlank { "Default" } ?: "Default",
            driverSysmem = (GeneralSettings["gpu_driver_force_sysmem"] as? Boolean) == true,
            settings = listOf(
                setting("Resolution", "Video@@Resolution", "720p"),
                setting("Aspect", "Video@@Aspect ratio", "16:9"),
                setting("Frame limit", "Video@@Frame limit", "Auto"),
                setting("VSync", "Video@@VSync", "Off"),
                setting("PPU", "Core@@PPU Decoder", "LLVM"),
                setting("SPU", "Core@@SPU Decoder", "LLVM")
            ),
            ppuStatus = when (availability) {
                GameLaunchAvailability.Ready -> "Ready"
                GameLaunchAvailability.NeedsPreparation -> "Needs preparation"
                is GameLaunchAvailability.PreparingPpu -> "Preparing PPU"
                is GameLaunchAvailability.Failed -> "Failed"
                else -> if (sameRunning) "Running" else "Unavailable"
            },
            saveSlots = slots,
            latestSave = latest,
            canPlayFresh = !otherRunning && (sameRunning || availability is GameLaunchAvailability.Ready),
            blockReason = blocked
        )
    }
}
