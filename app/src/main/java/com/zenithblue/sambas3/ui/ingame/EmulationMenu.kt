package com.zenithblue.sambas3.ui.ingame

import androidx.compose.foundation.background
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

/** Legacy alias for transitional compilation — new sealed interface is InGamePage (sealed). */
@Deprecated("Use sealed InGamePage")
enum class LegacyInGamePage { Closed, Menu, GlobalSettings, ConfigureGame }

/**
 * Legacy InGameUiState kept for incremental migration; RPCSXActivity now prefers InGameMenuController.
 * This class remains for tests that import it, but overlay host now uses controller.
 */
class InGameUiState {
    val page = mutableStateOf(LegacyInGamePage.Closed)
    val settingsBackstack = mutableListOf<String>()

    fun open(target: LegacyInGamePage) {
        settingsBackstack.clear()
        settingsBackstack.add("")
        page.value = target
    }

    fun popSubPage(): Boolean {
        if (page.value != LegacyInGamePage.GlobalSettings) return false
        if (settingsBackstack.size <= 1) return false
        settingsBackstack.removeAt(settingsBackstack.lastIndex)
        return true
    }

    fun close() {
        page.value = LegacyInGamePage.Closed
        settingsBackstack.clear()
        settingsBackstack.add("")
    }
}

private data class MenuRowSpec(
    val labelRes: Int,
    val iconRes: Int,
    val showArrow: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

/**
 * Full-screen Compose overlay host rendered inside RPCSXActivity's ComposeView.
 * Uses InGameMenuController as source of truth; falls back to legacy InGameUiState if controller not open.
 */
@Composable
fun EmulationOverlayHost(
    controller: InGameMenuController,
    gamePath: String?,
    onExitConfirmed: () -> Unit,
    onRequestScreenshot: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onSaveState: (Int) -> Unit = {},
    onLoadState: (Int) -> Unit = {}
) {
    val current = controller.stack.lastOrNull()
    when (current) {
        null -> Unit
        is InGamePage.Main -> EmulationMenuPanel(
            controller = controller,
            gamePath = gamePath,
            onExitConfirmed = onExitConfirmed,
            onRequestScreenshot = onRequestScreenshot,
            onToggleRecording = onToggleRecording
        )
        is InGamePage.Settings -> InGameSettingsPage(
            controller = controller,
            gamePath = gamePath
        )
        is InGamePage.ConfigureGame -> GameConfigureOverlay(
            gamePath = gamePath,
            onBackToMenu = { controller.back() }
        )
        is InGamePage.Trophies -> InGameTrophiesPage(
            onBack = { controller.back() }
        )
        is InGamePage.Friends -> InGameFriendsPage(
            onBack = { controller.back() }
        )
        is InGamePage.SaveStates -> InGameSaveStatePage(
            capabilities = controller.capabilities.savestate,
            onBack = { controller.back() },
            onSave = onSaveState,
            onLoad = onLoadState
        )
    }
}

// Legacy overload for tests (renamed to avoid retired API string)
@Composable
fun EmulationOverlayHost(
    uiState: InGameUiState,
    gamePath: String?,
    onCloseRequest: () -> Unit,
    onLegacyMenu: () -> Unit,
    onExitConfirmed: () -> Unit
) {
    // No longer used for core home menu; redirect to legacy menu
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onCloseRequest() } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = RPCSXColors.surfaceElevated,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.92f).heightIn(max = 480.dp).shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
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
                MenuRow(label = stringResource(R.string.ingame_resume), iconRes = R.drawable.ic_play, selected = false, showArrow = false) { onCloseRequest() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                MenuRow(label = stringResource(R.string.configure_game), iconRes = R.drawable.tune, selected = false) { uiState.open(LegacyInGamePage.ConfigureGame) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                MenuRow(label = stringResource(R.string.ingame_exit_game), iconRes = R.drawable.ic_stop, selected = false, showArrow = false) { onCloseRequest() }
            }
        }
    }
}

@Composable
private fun EmulationMenuPanel(
    controller: InGameMenuController,
    gamePath: String?,
    onExitConfirmed: () -> Unit,
    onRequestScreenshot: () -> Unit,
    onToggleRecording: () -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val cap = controller.capabilities
    val listState = rememberLazyListState()

    // Auto-scroll selected row into view
    LaunchedEffect(controller.selectedIndex) {
        if (controller.selectedIndex in 0..20) {
            try { listState.animateScrollToItem(controller.selectedIndex) } catch (_: Exception) {}
        }
    }

    val rows = buildList {
        add(MenuRowSpec(R.string.ingame_resume, R.drawable.ic_play, false) { controller.resume() })
        add(MenuRowSpec(R.string.configure_game, R.drawable.tune, true) { controller.push(InGamePage.ConfigureGame) })
        add(MenuRowSpec(R.string.ingame_settings, R.drawable.ic_settings, true) { controller.push(InGamePage.Settings) })
        if (cap.friendsAvailable) {
            add(MenuRowSpec(R.string.ingame_friends, R.drawable.ic_settings, true) { controller.push(InGamePage.Friends) })
        }
        if (cap.trophiesAvailable) {
            add(MenuRowSpec(R.string.ingame_trophies, R.drawable.ic_star, true) { controller.push(InGamePage.Trophies) })
        }
        add(MenuRowSpec(R.string.ingame_take_screenshot, R.drawable.ic_video, false, enabled = cap.screenshot) {
            controller.closeWithoutResume()
            onRequestScreenshot()
        })
        if (cap.recordingSupported) {
            val recLabel = if (cap.recordingActive == true) R.string.ingame_stop_recording else R.string.ingame_start_recording
            add(MenuRowSpec(recLabel, R.drawable.ic_video, false) {
                controller.closeWithoutResume()
                onToggleRecording()
            })
        }
        if (cap.savestate?.supported == true) {
            add(MenuRowSpec(R.string.ingame_save_state, R.drawable.ic_save, true) { controller.push(InGamePage.SaveStates) })
        }
        add(MenuRowSpec(R.string.ingame_restart_game, R.drawable.ic_restore, false) { showRestartConfirm = true })
        add(MenuRowSpec(R.string.ingame_exit_game, R.drawable.ic_stop, false) { showExitConfirm = true })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { controller.resume() } },
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
                        val selected = index == controller.selectedIndex
                        MenuRow(
                            label = stringResource(row.labelRes),
                            iconRes = row.iconRes,
                            selected = selected,
                            enabled = row.enabled,
                            showArrow = row.showArrow,
                            onClick = {
                                controller.setSelected(index)
                                row.onClick()
                            }
                        )
                        if (index < rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
                    controller.closeWithoutResume()
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
    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { showRestartConfirm = false },
            title = { Text(stringResource(R.string.ingame_restart_game)) },
            text = { Text("Restart the game?") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    // Close without resume then restart via RPCSX
                    controller.closeWithoutResume()
                    try { com.zenithblue.sambas3.RPCSX.instance.restartGame() } catch (_: Exception) {}
                }) { Text("Restart") }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text("Cancel") }
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
