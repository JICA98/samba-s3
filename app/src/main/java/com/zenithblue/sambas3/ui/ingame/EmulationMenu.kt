package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    gamePath: String?,
    onIntent: (InGameMenuIntent) -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val cap = uiState.capabilities
    val listState = rememberLazyListState()
    val selected = uiState.selectedIndex

    val rows = remember(cap) { mainRowDescriptors(cap) }

    // Exact actionable item count -> coordinator (never hard-coded).
    LaunchedEffect(rows.size) {
        onIntent(InGameMenuIntent.ReportItemCount(InGamePage.Main, rows.size))
    }

    // Bring selected row into view.
    LaunchedEffect(selected) {
        if (selected in rows.indices) {
            runCatching { listState.animateScrollToItem(selected) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onIntent(InGameMenuIntent.DismissOutside) } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = runningGameLabel(gamePath),
                    color = RPCSXColors.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                    itemsIndexed(rows) { index, row ->
                        val isSelected = index == selected
                        MenuRow(
                            label = stringResource(row.labelRes),
                            iconRes = row.iconRes,
                            selected = isSelected,
                            enabled = row.enabled,
                            showArrow = row.showArrow,
                            onClick = {
                                if (row.intent == InGameMenuIntent.Restart) {
                                    showRestartConfirm = true
                                } else {
                                    onIntent(row.intent)
                                }
                            }
                        )
                        if (index < rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }            }
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
private fun MenuRow(label: String, iconRes: Int, selected: Boolean, showArrow: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (selected) RPCSXColors.primary.copy(alpha = 0.15f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = if (enabled) RPCSXColors.primary else RPCSXColors.textSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label.uppercase(),
            color = if (enabled) RPCSXColors.textPrimary else RPCSXColors.textSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        if (showArrow) {
            Icon(
                painter = painterResource(id = R.drawable.ic_keyboard_arrow_right),
                contentDescription = null,
                tint = RPCSXColors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
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
