package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.games.GameConfigureOverlay

/**
 * Presentational in-game menu host: state in, intents out. No native calls,
 * no session ownership, no duplicated state.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InGameMenuHost(
    uiState: InGameMenuUiState,
    gamePath: String?,
    core: InGameMenuCoreGateway,
    onIntent: (InGameMenuIntent) -> Unit
) {
    when (uiState.currentPage) {
        null -> Unit
        InGamePage.Main -> InGameMainPanel(uiState, gamePath, onIntent)
        InGamePage.Settings -> InGameSettingsPage(uiState, core, onIntent)
        InGamePage.Monitoring -> com.zenithblue.sambas3.ui.monitoring.MonitoringSettingsScreen(
            navigateBack = { onIntent(InGameMenuIntent.Back) },
            isInSplitPane = true
        )
        InGamePage.LiveLogs -> com.zenithblue.sambas3.ui.logging.InGameLiveLogsPage(
            onBack = { onIntent(InGameMenuIntent.Back) },
        )
        InGamePage.Controller -> com.zenithblue.sambas3.ui.controller.ControllerSettingsScreen(
            navigateBack = { onIntent(InGameMenuIntent.Back) },
            isInSplitPane = true
        )
        InGamePage.ConfigureGame -> GameConfigureOverlayRoute(uiState, gamePath, onIntent)
        InGamePage.Trophies -> InGameTrophiesPage(core = core, onBack = { onIntent(InGameMenuIntent.Back) })
        InGamePage.Friends -> InGameFriendsPage(core = core, onBack = { onIntent(InGameMenuIntent.Back) })
        InGamePage.SaveStates -> InGameSaveStatePage(
            capabilities = uiState.capabilities.savestate,
            onBack = { onIntent(InGameMenuIntent.Back) },
            onSave = { onIntent(InGameMenuIntent.SaveState(it)) },
            onLoad = { onIntent(InGameMenuIntent.LoadState(it)) }
        )
    }
}

@Composable
private fun GameConfigureOverlayRoute(uiState: InGameMenuUiState, gamePath: String?, onIntent: (InGameMenuIntent) -> Unit) {
    GameConfigureOverlay(gamePath = gamePath, onBackToMenu = { onIntent(InGameMenuIntent.Back) })
}

@Composable
private fun InGameMainPanel(
    uiState: InGameMenuUiState,
    @Suppress("UNUSED_PARAMETER") gamePath: String?,
    onIntent: (InGameMenuIntent) -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val cap = uiState.capabilities
    val gridState = rememberLazyGridState()
    val selected = uiState.selectedIndex

    val rows = remember(cap) { mainRowDescriptors(cap) }

    // Exact actionable item count -> coordinator (never hard-coded).
    LaunchedEffect(rows.size) {
        onIntent(InGameMenuIntent.ReportItemCount(InGamePage.Main, rows.size))
    }

    // Bring selected row into view.
    LaunchedEffect(selected) {
        if (selected in rows.indices) {
            runCatching { gridState.animateScrollToItem(selected) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .pointerInput(Unit) { detectTapGestures { onIntent(InGameMenuIntent.DismissOutside) } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.88f)
                .padding(4.dp)
                .navigationBarsPadding()
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Header (No game title or image, as per retro console pause menu standard)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_home_menu),
                            contentDescription = null,
                            tint = RPCSXColors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "PAUSE MENU",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    TextButton(
                        onClick = { onIntent(InGameMenuIntent.Resume) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("RESUME", color = RPCSXColors.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                // 2-Column Grid optimized for 21:9 and widescreen layouts
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(rows) { index, row ->
                        val isSelected = index == selected
                        MenuTile(
                            label = stringResource(row.labelRes),
                            iconRes = row.iconRes,
                            selected = isSelected,
                            enabled = row.enabled,
                            showArrow = row.showArrow,
                            isDestructive = row.intent == InGameMenuIntent.Exit || row.intent == InGameMenuIntent.Restart,
                            onClick = {
                                if (row.intent == InGameMenuIntent.Exit) {
                                    showExitConfirm = true
                                } else if (row.intent == InGameMenuIntent.Restart) {
                                    showRestartConfirm = true
                                } else {
                                    onIntent(row.intent)
                                }
                            }
                        )
                    }
                }

                // Footer with status and quick button hints
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EMULATION PAUSED",
                        color = RPCSXColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = RPCSXColors.surfaceOverlay,
                                modifier = Modifier.size(16.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "X",
                                        color = RPCSXColors.primary,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Text("SELECT", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = RPCSXColors.surfaceOverlay,
                                modifier = Modifier.size(16.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "O",
                                        color = RPCSXColors.textSecondary,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                            Text("RESUME", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.ingame_exit_game)) },
            text = { Text(stringResource(R.string.exit_game_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    onIntent(InGameMenuIntent.Exit)
                }) { Text(stringResource(R.string.exit_game_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.exit_game_confirm_no))
                }
            }
        )
    }
    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.ingame_restart_game)) },
            text = { Text(stringResource(R.string.ingame_restart_game) + "?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    onIntent(InGameMenuIntent.Restart)
                }) { Text(stringResource(R.string.ingame_restart_game)) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text(stringResource(R.string.exit_game_confirm_no)) }
            }
        )
    }
}

@Composable
private fun MenuTile(
    label: String,
    iconRes: Int,
    selected: Boolean,
    enabled: Boolean = true,
    showArrow: Boolean = true,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected && isDestructive -> RPCSXColors.errorColor
        selected -> RPCSXColors.focusRing
        isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val containerColor = when {
        selected && isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.15f)
        selected -> RPCSXColors.primary.copy(alpha = 0.14f)
        else -> RPCSXColors.surface
    }
    val iconTint = when {
        !enabled -> RPCSXColors.textSecondary.copy(alpha = 0.4f)
        isDestructive && selected -> RPCSXColors.errorColor
        isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.85f)
        selected -> RPCSXColors.primary
        else -> RPCSXColors.primary.copy(alpha = 0.85f)
    }
    val textColor = when {
        !enabled -> RPCSXColors.textSecondary.copy(alpha = 0.4f)
        isDestructive && selected -> RPCSXColors.errorColor
        selected -> RPCSXColors.primary
        else -> RPCSXColors.textPrimary
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when {
                            selected && isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.2f)
                            selected -> RPCSXColors.primary.copy(alpha = 0.2f)
                            else -> RPCSXColors.surfaceOverlay
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label.uppercase(),
                color = textColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showArrow) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_keyboard_arrow_right),
                    contentDescription = null,
                    tint = if (selected) RPCSXColors.primary else RPCSXColors.textSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun runningGameLabel(gamePath: String?): String {
    val fallback = stringResource(R.string.ingame_menu_title)
    if (gamePath.isNullOrBlank()) return fallback
    GameRepository.find(gamePath)?.info?.name?.value?.takeIf { it.isNotBlank() }
        ?.let { return it.uppercase() }
    val segment = gamePath.trimEnd('/').substringAfterLast('/').trim()
    return segment.ifBlank { gamePath }.uppercase()
}
