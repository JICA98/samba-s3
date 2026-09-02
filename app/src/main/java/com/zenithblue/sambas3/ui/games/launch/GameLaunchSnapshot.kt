package com.zenithblue.sambas3.ui.games.launch

import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.ui.ingame.SaveSlot

data class LaunchSetting(val label: String, val value: String, val source: String)

data class GameLaunchSnapshot(
    val game: Game,
    val titleId: String?,
    val selectedDriver: String,
    val driverSysmem: Boolean,
    val settings: List<LaunchSetting>,
    val ppuStatus: String,
    val ppuUi: LaunchPpuUi,
    val saveSlots: List<SaveSlot>,
    val latestSave: SaveSlot?,
    val canPlayFresh: Boolean,
    val canLoadSave: Boolean,
    val blockReason: String? = null,
    val compactEmptySaves: Boolean = false,
)
