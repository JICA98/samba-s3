package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
/** In-overlay page state machine owned by RPCSXActivity (P2 step 1). */
enum class InGamePage { Closed, Menu, GlobalSettings, ConfigureGame }

/**
 * Shared in-game UI state: current overlay page plus the advanced-settings
 * backstack used by the in-game settings page and the activity's back callback.
 */
class InGameUiState {
    val page = mutableStateOf(InGamePage.Closed)
    val settingsBackstack = mutableStateListOf("")

    fun open(target: InGamePage) {
        settingsBackstack.clear()
        settingsBackstack.add("")
        page.value = target
    }

    /** @return true when a sub-page was popped; false when the page itself must close. */
    fun popSubPage(): Boolean {
        if (page.value != InGamePage.GlobalSettings) return false
        if (settingsBackstack.size <= 1) return false
        settingsBackstack.removeAt(settingsBackstack.lastIndex)
        return true
    }

    fun close() {
        page.value = InGamePage.Closed
        settingsBackstack.clear()
        settingsBackstack.add("")
    }
}

private data class MenuRowSpec(
    val labelRes: Int,
    val iconRes: Int,
    val action: EmulationMenuAction
)

private enum class EmulationMenuAction { Resume, ConfigureGame, GlobalSettings, CoreHomeMenu, Exit }

/**
 * Full-screen Compose overlay host rendered inside RPCSXActivity's ComposeView.
 * Closed emits nothing; any other page draws a modal scrim that consumes game
 * touches while open (PadOverlay's menu-mode gate is the belt-and-braces fallback).
 */
@Composable
fun EmulationOverlayHost(
    uiState: InGameUiState,
    gamePath: String?,
    onCloseRequest: () -> Unit,
    onOpenCoreHomeMenu: () -> Unit,
    onExitConfirmed: () -> Unit
) {
    when (uiState.page.value) {
        InGamePage.Closed -> Unit

        InGamePage.Menu -> EmulationMenuPanel(
            gamePath = gamePath,
            onDismiss = onCloseRequest,
            onNavigate = { uiState.open(it) },
            onOpenCoreHomeMenu = onOpenCoreHomeMenu,
            onExitConfirmed = onExitConfirmed
        )

        InGamePage.GlobalSettings -> InGameSettingsPage(
            uiState = uiState,
            onBackToMenu = { uiState.open(InGamePage.Menu) }
        )

        InGamePage.ConfigureGame -> GameConfigureOverlay(
            gamePath = gamePath,
            onBackToMenu = { uiState.open(InGamePage.Menu) }
        )
    }
}

@Composable
private fun EmulationMenuPanel(
    gamePath: String?,
    onDismiss: () -> Unit,
    onNavigate: (InGamePage) -> Unit,
    onOpenCoreHomeMenu: () -> Unit,
    onExitConfirmed: () -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 480.dp)
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

                listOf(
                    MenuRowSpec(R.string.ingame_resume, R.drawable.ic_play, EmulationMenuAction.Resume),
                    MenuRowSpec(R.string.configure_game, R.drawable.tune, EmulationMenuAction.ConfigureGame),
                    MenuRowSpec(R.string.ingame_global_settings, R.drawable.ic_settings, EmulationMenuAction.GlobalSettings),
                    MenuRowSpec(R.string.ingame_core_home_menu, R.drawable.ic_home_menu, EmulationMenuAction.CoreHomeMenu),
                    MenuRowSpec(R.string.ingame_exit_game, R.drawable.ic_stop, EmulationMenuAction.Exit)
                ).forEachIndexed { index, row ->
                    MenuRow(row = row) {
                        when (row.action) {
                            EmulationMenuAction.Resume -> onDismiss()
                            EmulationMenuAction.ConfigureGame -> onNavigate(InGamePage.ConfigureGame)
                            EmulationMenuAction.GlobalSettings -> onNavigate(InGamePage.GlobalSettings)
                            EmulationMenuAction.CoreHomeMenu -> {
                                onDismiss()
                                onOpenCoreHomeMenu()
                            }
                            EmulationMenuAction.Exit -> showExitConfirm = true
                        }
                    }
                    if (index < 4) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                    onExitConfirmed()
                }) { Text(stringResource(R.string.exit_game_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.exit_game_confirm_no))
                }
            }
        )
    }
}

@Composable
private fun MenuRow(row: MenuRowSpec, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Icon(
            painter = painterResource(id = row.iconRes),
            contentDescription = null,
            tint = RPCSXColors.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(row.labelRes).uppercase(),
            color = RPCSXColors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(id = R.drawable.ic_keyboard_arrow_right),
            contentDescription = null,
            tint = RPCSXColors.textSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Friendly running-game label for the panel header: GameRepository display name,
 * then the TITLE_ID-shaped last path segment, then the raw path string.
 */
@Composable
fun runningGameLabel(gamePath: String?): String {
    val fallback = stringResource(R.string.ingame_menu_title)
    if (gamePath.isNullOrBlank()) return fallback
    GameRepository.find(gamePath)?.info?.name?.value?.takeIf { it.isNotBlank() }
        ?.let { return it.uppercase() }
    val segment = gamePath.trimEnd('/').substringAfterLast('/').trim()
    return segment.ifBlank { gamePath }.uppercase()
}

