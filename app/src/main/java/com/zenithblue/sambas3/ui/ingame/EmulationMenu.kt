package com.zenithblue.sambas3.ui.ingame

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.games.GameConfigureOverlay
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    if (uiState.currentPage == null) return

    val context = LocalContext.current
    val game = remember(gamePath) {
        if (gamePath.isNullOrBlank()) null
        else {
            GameRepository.find(gamePath)
                ?: GameRepository.list().find {
                    it.info.path.equals(gamePath, ignoreCase = true) ||
                    it.info.path.endsWith(gamePath, ignoreCase = true) ||
                    gamePath.endsWith(it.info.path, ignoreCase = true) ||
                    it.info.name.value.equals(gamePath, ignoreCase = true)
                }
        }
    }

    val rawIconPath = game?.info?.iconPath?.value
    val iconPreview = remember(rawIconPath, gamePath) {
        if (!rawIconPath.isNullOrBlank()) {
            GamePreviewRepository.resolveInstalledPreview(rawIconPath)
        } else if (!gamePath.isNullOrBlank()) {
            GamePreviewRepository.resolveInstalledPreview(gamePath)
        } else {
            GamePreviewModel.None
        }
    }
    val gameIconModel: Any? = when (iconPreview) {
        is GamePreviewModel.LocalFile -> iconPreview.file
        is GamePreviewModel.ContentUri -> iconPreview.uri
        is GamePreviewModel.None -> null
    }

    val titleId = remember(game, gamePath) {
        gamePath?.let { GameIdentity.titleIdOrNull(it, game?.info?.name?.value) }
            ?: game?.info?.path?.substringAfterLast('/')
    }

    val bgPreview by produceState<Any?>(
        initialValue = null,
        key1 = game?.info?.path ?: gamePath,
        key2 = rawIconPath
    ) {
        value = withContext(Dispatchers.IO) {
            // 1. Check direct_iso_icons/${titleId}_pic1.png
            if (!titleId.isNullOrBlank()) {
                val directIsoPic1 = File(context.filesDir, "direct_iso_icons/${titleId}_pic1.png")
                if (directIsoPic1.isFile && directIsoPic1.length() > 0) {
                    return@withContext directIsoPic1
                }
            }
            // 2. Check GamePreviewRepository.resolveBackground(context, game)
            if (game != null) {
                when (val bg = GamePreviewRepository.resolveBackground(context, game)) {
                    is GamePreviewModel.LocalFile -> if (bg.file.exists() && bg.file.length() > 0) return@withContext bg.file
                    is GamePreviewModel.ContentUri -> return@withContext bg.uri
                    is GamePreviewModel.None -> Unit
                }
            }
            // 3. Check installed background from rawIconPath
            if (!rawIconPath.isNullOrBlank()) {
                when (val bg = GamePreviewRepository.resolveInstalledBackground(rawIconPath)) {
                    is GamePreviewModel.LocalFile -> if (bg.file.exists() && bg.file.length() > 0) return@withContext bg.file
                    is GamePreviewModel.ContentUri -> return@withContext bg.uri
                    is GamePreviewModel.None -> Unit
                }
            }
            // 4. Check direct_iso_icons/${titleId}.png
            if (!titleId.isNullOrBlank()) {
                val directIsoIcon = File(context.filesDir, "direct_iso_icons/${titleId}.png")
                if (directIsoIcon.isFile && directIsoIcon.length() > 0) {
                    return@withContext directIsoIcon
                }
            }
            null
        }
    }
    val artworkModel = bgPreview ?: gameIconModel

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.70f))
    ) {
        // Edge-to-edge ambient artwork background
        if (artworkModel != null) {
            AsyncImage(
                model = artworkModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.05f)
                    .alpha(0.40f)
            )
        }

        // Dark frosted gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF20F121A),
                            Color(0xEB111520),
                            Color(0xF5080A0F)
                        )
                    )
                )
        )

        when (uiState.currentPage) {
            InGamePage.Main -> InGameMainPanel(uiState, game, gamePath, gameIconModel, artworkModel, onIntent)
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
            null -> Unit
        }
    }
}

@Composable
private fun GameConfigureOverlayRoute(uiState: InGameMenuUiState, gamePath: String?, onIntent: (InGameMenuIntent) -> Unit) {
    GameConfigureOverlay(gamePath = gamePath, onBackToMenu = { onIntent(InGameMenuIntent.Back) })
}

@Composable
private fun InGameMainPanel(
    uiState: InGameMenuUiState,
    game: com.zenithblue.sambas3.Game?,
    gamePath: String?,
    gameIconModel: Any?,
    artworkModel: Any?,
    onIntent: (InGameMenuIntent) -> Unit
) {
    var showExitConfirm by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    val cap = uiState.capabilities
    val listState = rememberLazyListState()
    val selected = uiState.selectedIndex

    val rows = remember(cap) { mainRowDescriptors(cap) }

    LaunchedEffect(rows.size) {
        onIntent(InGameMenuIntent.ReportItemCount(InGamePage.Main, rows.size))
    }

    LaunchedEffect(selected) {
        if (selected in rows.indices) {
            runCatching { listState.animateScrollToItem(selected) }
        }
    }

    val fallbackTitle = stringResource(R.string.ingame_menu_title)
    val gameTitle = remember(game, gamePath, fallbackTitle) {
        game?.let { GameIdentity.displayName(it.info.path, it.info.name.value) }
            ?: gamePath?.trimEnd('/')?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
    }

    val titleId = remember(game, gamePath) {
        gamePath?.let { GameIdentity.titleIdOrNull(it, game?.info?.name?.value) }
            ?: game?.info?.path?.substringAfterLast('/')
    }

    val isIso = remember(game, gamePath) {
        game?.info?.sourceMode?.value == com.zenithblue.sambas3.GameSourceMode.DIRECT_ISO ||
            gamePath?.contains("direct_iso", ignoreCase = true) == true ||
            gamePath?.endsWith(".iso", ignoreCase = true) == true ||
            game?.info?.sourceUri?.value?.endsWith(".iso", ignoreCase = true) == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                // Game Logo: Generous size (54dp), padded so outline does NOT touch logo image
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x28FFFFFF),
                    border = BorderStroke(1.5.dp, RPCSXColors.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.size(54.dp)
                ) {
                    Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                        if (gameIconModel != null) {
                            AsyncImage(
                                model = gameIconModel,
                                contentDescription = gameTitle,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_home_menu),
                                contentDescription = null,
                                tint = RPCSXColors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.Center) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "MENU",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                        )
                        if (isIso) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = RPCSXColors.primary.copy(alpha = 0.2f),
                                border = BorderStroke(0.5.dp, RPCSXColors.primary.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    text = "ISO",
                                    color = RPCSXColors.primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = gameTitle,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right side Resume button pill
            Surface(
                onClick = { onIntent(InGameMenuIntent.Resume) },
                shape = RoundedCornerShape(8.dp),
                color = RPCSXColors.primary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.4f)),
                modifier = Modifier.height(34.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        tint = RPCSXColors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "RESUME",
                        color = RPCSXColors.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = Color(0x22FFFFFF)
        )

        // 21:9 Widescreen Two-Column Layout: Single-column left-aligned menu on left, Stats & Hero on right
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Single Column Menu (Left-aligned)
            LazyColumn(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
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

            // Right side: Hero Artwork & Session Stats Card (SS3-D-008 inspired)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0x28141926),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Game Cover / Artwork Banner
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0x20000000),
                            border = BorderStroke(1.dp, Color(0x25FFFFFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        ) {
                            if (artworkModel != null) {
                                AsyncImage(
                                    model = artworkModel,
                                    contentDescription = gameTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else if (gameIconModel != null) {
                                AsyncImage(
                                    model = gameIconModel,
                                    contentDescription = gameTitle,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(12.dp)
                                )
                            }
                        }

                        Text(
                            text = gameTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isIso) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = RPCSXColors.primary.copy(alpha = 0.2f),
                                    border = BorderStroke(0.5.dp, RPCSXColors.primary.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        "ISO",
                                        color = RPCSXColors.primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            titleId?.let { id ->
                                Text(
                                    id.uppercase(),
                                    color = RPCSXColors.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Session / Emulation Status Strip
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x20000000),
                        border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TARGET", color = RPCSXColors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text("60 FPS", color = RPCSXColors.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(Modifier.width(1.dp).height(24.dp).background(Color(0x20FFFFFF)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("STATE", color = RPCSXColors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text("PAUSED", color = RPCSXColors.focusRing, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(Modifier.width(1.dp).height(24.dp).background(Color(0x20FFFFFF)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("CORE", color = RPCSXColors.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                Text("RPCSX", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Footer with status and quick button hints
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color(0x18FFFFFF)
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

    if (showExitConfirm) {
        CustomConfirmDialog(
            title = stringResource(R.string.ingame_exit_game),
            message = stringResource(R.string.exit_game_confirm_message),
            iconRes = R.drawable.ic_stop,
            confirmText = "EXIT",
            confirmColor = RPCSXColors.errorColor,
            onConfirm = {
                showExitConfirm = false
                onIntent(InGameMenuIntent.Exit)
            },
            onDismiss = { showExitConfirm = false }
        )
    }
    if (showRestartConfirm) {
        CustomConfirmDialog(
            title = stringResource(R.string.ingame_restart_game),
            message = "Restart the current game session from the beginning?",
            iconRes = R.drawable.ic_restore,
            confirmText = "RESTART",
            confirmColor = RPCSXColors.focusRing,
            onConfirm = {
                showRestartConfirm = false
                onIntent(InGameMenuIntent.Restart)
            },
            onDismiss = { showRestartConfirm = false }
        )
    }
}

@Composable
private fun CustomConfirmDialog(
    title: String,
    message: String,
    iconRes: Int,
    confirmText: String,
    confirmColor: Color = RPCSXColors.primary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF8111520),
            border = BorderStroke(1.dp, Color(0x35FFFFFF)),
            shadowElevation = 24.dp,
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = confirmColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, confirmColor.copy(alpha = 0.4f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = null,
                                tint = confirmColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = message,
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0x30FFFFFF)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text("NO", color = RPCSXColors.textSecondary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(8.dp),
                        color = confirmColor.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, confirmColor.copy(alpha = 0.7f)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(Modifier.padding(horizontal = 16.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(confirmText, color = confirmColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
    val scale by animateFloatAsState(if (selected) 1.02f else 1.0f, label = "tileScale")

    val borderColor = when {
        selected && isDestructive -> RPCSXColors.errorColor
        selected -> RPCSXColors.focusRing
        isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.35f)
        else -> Color(0x28FFFFFF)
    }
    val containerColor = when {
        selected && isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.18f)
        selected -> RPCSXColors.primary.copy(alpha = 0.20f)
        isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.06f)
        else -> Color(0x28161A24)
    }
    val iconTint = when {
        !enabled -> RPCSXColors.textSecondary.copy(alpha = 0.4f)
        isDestructive && selected -> RPCSXColors.errorColor
        isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.85f)
        selected -> RPCSXColors.focusRing
        else -> RPCSXColors.primary.copy(alpha = 0.85f)
    }
    val textColor = when {
        !enabled -> RPCSXColors.textSecondary.copy(alpha = 0.4f)
        isDestructive && selected -> RPCSXColors.errorColor
        selected -> RPCSXColors.focusRing
        else -> RPCSXColors.textPrimary
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(scale)
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
                            selected && isDestructive -> RPCSXColors.errorColor.copy(alpha = 0.25f)
                            selected -> RPCSXColors.primary.copy(alpha = 0.30f)
                            else -> Color(0x20FFFFFF)
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
                    tint = if (selected) RPCSXColors.focusRing else Color(0x44FFFFFF),
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
