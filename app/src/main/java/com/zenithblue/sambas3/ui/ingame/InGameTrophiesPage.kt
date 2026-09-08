package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.ui.achievements.AchievementRepository
import com.zenithblue.sambas3.ui.achievements.AchievementsContent

@Composable
fun InGameTrophiesPage(core: InGameMenuCoreGateway, onBack: () -> Unit) {
    var snapshot by remember { mutableStateOf<TrophiesData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { TrophyEvents.refreshes.collect { refreshTick++ } }
    LaunchedEffect(refreshTick) {
        loading = true
        snapshot = core.trophies().getOrNull() ?: AchievementRepository.current(force = true)
        loading = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        AchievementsContent(snapshot, loading, onBack)
    }
}
