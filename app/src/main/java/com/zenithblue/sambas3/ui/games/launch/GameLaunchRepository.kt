package com.zenithblue.sambas3.ui.games.launch

import android.content.Context
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec
import com.zenithblue.sambas3.ppu.GameLaunchAvailability
import com.zenithblue.sambas3.ppu.GameRunEligibilityHelper
import com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator
import com.zenithblue.sambas3.utils.GeneralSettings

object GameLaunchRepository {
    fun snapshot(
        context: Context,
        game: Game,
        inputs: LaunchRuntimeInputs? = null,
    ): GameLaunchSnapshot {
        val titleId = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
        val live = inputs ?: LaunchRuntimeInputs(
            installPpu = CompileProgressBridge.installState.value,
            prelaunchPpu = CompileProgressBridge.prelaunchState.value,
            runtimePpu = CompileProgressBridge.state.value,
            emulatorState = RPCSX.state.value,
            activeGame = RPCSX.activeGame.value,
            waitingForIdle = ImportPpuPreparationCoordinator.waitingForIdle,
            deferredForFgs = ImportPpuPreparationCoordinator.deferredForFgs,
            fgsStartDenied = CompileProgressBridge.fgsStartDenied,
            preRuntimeState = titleId?.let {
                runCatching { PpuReadinessStore.getPreRuntimeState(context, it) }.getOrDefault(PreRuntimePpuState.NOT_DONE)
            } ?: PreRuntimePpuState.NOT_DONE,
            runtimeReadyState = titleId?.let {
                runCatching { PpuReadinessStore.getRuntimeState(context, it) }.getOrDefault(RuntimePpuState.NOT_STARTED)
            } ?: RuntimePpuState.NOT_STARTED,
            validatedByRealBootFrame = titleId?.let {
                runCatching { PpuReadinessStore.isRuntimeValidated(context, it) }.getOrDefault(false)
            } ?: false,
        )
        val overrides = titleId?.let { GameSettingsOverrides.gameOverrides(context, it) }.orEmpty()
        fun globalValue(path: String, fallback: String): String {
            return runCatching {
                val node = org.json.JSONObject(RPCSX.instance.settingsGetGlobal(path))
                val type = node.optString("type")
                val display = when (type) {
                    "bool" -> node.optBoolean("value").toString()
                    else -> node.optString("value", fallback)
                }
                SettingsValueCodec.encodedFromNode(
                    SettingsValueCodec.SettingNodeSpec(type = type), display
                )
            }.getOrDefault(fallback)
        }
        fun setting(label: String, path: String, fallback: String): LaunchSetting {
            val value = overrides[path] ?: globalValue(path, fallback)
            return LaunchSetting(label, value, if (path in overrides) "GAME" else "GLOBAL")
        }
        val installActive = LaunchPpuPresentation.installActiveForTitle(live.installPpu, titleId)
        val availability = GameRunEligibilityHelper.evaluateAvailability(
            context,
            game,
            installActive,
            live.prelaunchPpu,
            live.runtimePpu,
            live.emulatorState,
            live.activeGame,
        )
        val ppuUi = LaunchPpuPresentation.build(titleId, availability, live)
        val slots = GameSavestateRepository.slots(context, game)
        val latest = slots.filter { it.exists }.maxByOrNull { it.mtimeMs }
        val hasSaves = slots.any { it.exists }
        val active = live.activeGame
        val sameRunning = active == game.info.path &&
            (live.emulatorState == EmulatorState.Running || live.emulatorState == EmulatorState.Paused)
        val otherRunning = active != null && active != game.info.path &&
            (live.emulatorState == EmulatorState.Running || live.emulatorState == EmulatorState.Paused)
        val blocked = when {
            otherRunning -> "Another game is already running"
            availability is GameLaunchAvailability.GameplayRunning && !sameRunning -> "An emulator session is already running"
            availability is GameLaunchAvailability.EngineBusy -> "Emulator busy (${availability.state})"
            availability is GameLaunchAvailability.PreparingPpu -> ppuUi.statusLine ?: "Preparing PPU"
            availability is GameLaunchAvailability.Importing -> "Import still in progress"
            availability is GameLaunchAvailability.NeedsPreparation -> ppuUi.statusLine ?: "PPU preparation required"
            availability is GameLaunchAvailability.Failed -> availability.reason
            else -> ppuUi.statusLine
        }
        return GameLaunchSnapshot(
            game = game,
            titleId = titleId,
            selectedDriver = runCatching {
                (GeneralSettings["selected_gpu_driver"] as? String)?.ifBlank { "Default" } ?: "Default"
            }.getOrDefault("Default"),
            driverSysmem = runCatching {
                (GeneralSettings["gpu_driver_force_sysmem"] as? Boolean) == true
            }.getOrDefault(false),
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
            ppuUi = ppuUi,
            saveSlots = slots,
            latestSave = latest,
            canPlayFresh = ppuUi.startEnabled,
            canLoadSave = ppuUi.startEnabled,
            blockReason = blocked,
            compactEmptySaves = LaunchPpuPresentation.compactEmptySaves(hasSaves),
        )
    }
}
