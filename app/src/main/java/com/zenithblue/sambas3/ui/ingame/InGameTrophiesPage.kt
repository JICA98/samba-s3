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
        // TROPHY REGRESSION BARRIER — DO NOT SIMPLIFY (2026-09-17). The
        // gateway already falls back from live `current()` to explicit
        // titleId, but the page keeps its own fallback as well: if the
        // gateway result is unavailable (old core, empty live context),
        // retry live then explicit titleId directly. This mirrors the
        // launcher (GamesScreen stopped-title + ISO fallback) so in-game
        // can never show 0/0 while the launcher shows 0/52 for the same
        // installed set. See RpcsxInGameMenuCoreGateway.trophies().
        val gatewayResult = core.trophies().getOrNull()
        snapshot = if (gatewayResult != null && gatewayResult.available) {
            gatewayResult
        } else {
            val live = AchievementRepository.current(force = true)
            if (live != null && live.available) live else {
                val titleId = runCatching { com.zenithblue.sambas3.RPCSX.instance.getTitleId() }
                    .getOrNull()?.trim().orEmpty()
                if (titleId.isNotEmpty()) {
                    AchievementRepository.title(titleId, force = true)?.takeIf { it.available } ?: live
                } else live
            }
        }
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
