package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zenithblue.sambas3.RPCSXColors
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
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .70f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f)
        ) {
            AchievementsContent(snapshot, loading, onBack)
        }
    }
}
