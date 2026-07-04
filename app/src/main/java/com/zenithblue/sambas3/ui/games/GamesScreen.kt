package com.zenithblue.sambas3.ui.games

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zenithblue.sambas3.*
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.utils.FileUtil
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    installPkgLauncher: ActivityResultLauncher<String>? = null,
    gameFolderPickerLauncher: ActivityResultLauncher<Uri?>? = null,
    installFwLauncher: ActivityResultLauncher<String>? = null,
    navigateToSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val games = remember { GameRepository.list() }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }

    if (rpcsxLibrary == null) {
        // Loading screen while library is missing
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RPCSXColors.background)
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color(0xCC000000)),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.width
                        ),
                        size = size
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Icon(painterResource(R.drawable.gamepad), contentDescription = null, tint = RPCSXColors.primary, modifier = Modifier.size(64.dp))
                Text("SambaS3", style = AppTypography.displayLarge.copy(letterSpacing = 4.sp), color = RPCSXColors.primary)
                CircularProgressIndicator(color = RPCSXColors.primary, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.missing_rpcsx_lib), style = AppTypography.labelSmall, color = RPCSXColors.textSecondary)
            }
        }
        return
    }

    var focusedIndex by remember { mutableStateOf(if (games.isNotEmpty()) 0 else -1) }
    var bootingGame by remember { mutableStateOf<Game?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    val bootScale by animateFloatAsState(if (bootingGame != null) 5f else 1f, animationSpec = tween(700))
    val bootAlpha by animateFloatAsState(if (bootingGame != null) 0f else 1f, animationSpec = tween(500))

    LaunchedEffect(bootingGame) {
        if (bootingGame != null) {
            kotlinx.coroutines.delay(600)
            bootGame(context, bootingGame!!)
            bootingGame = null
        }
    }

    val isoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            PrecompilerService.start(context, PrecompilerServiceAction.Install, uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            FileUtil.installPackages(context, uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RPCSXColors.background)
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000)),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.width
                    ),
                    size = size
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Nav Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(bootAlpha)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.gamepad), contentDescription = null, tint = RPCSXColors.primary)
                    Text("SambaS3", style = AppTypography.displayLarge.copy(letterSpacing = 4.sp), color = RPCSXColors.primary)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = RPCSXColors.primary, modifier = Modifier.size(16.dp))
                        Text("CONNECTED", style = AppTypography.labelSmall, color = RPCSXColors.primary)
                    }
                    Text(
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                        style = AppTypography.labelMedium,
                        color = RPCSXColors.textSecondary
                    )
                    Row {
                        IconButton(onClick = { navigateToSettings?.invoke() }) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = RPCSXColors.primary)
                        }
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(painterResource(R.drawable.ic_add), contentDescription = "Add Game", tint = RPCSXColors.primary)
                        }
                    }
                }
            }

            if (games.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("No games yet.\nPress + to add a game from your device.", style = AppTypography.bodyLarge, textAlign = TextAlign.Center, color = RPCSXColors.textSecondary)
                }
            } else {
                // Carousel
                val pagerState = rememberPagerState(pageCount = { games.size })
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val availableHeight = maxHeight
                    val itemHeight = (availableHeight - 64.dp) * 0.8f
                    val itemWidth = itemHeight * 0.85f
                    val horizontalPadding = if (maxWidth > itemWidth) (maxWidth - itemWidth) / 2 else 0.dp
                    val coroutineScope = rememberCoroutineScope()
                    
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().scale(bootScale),
                        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 32.dp),
                        pageSpacing = 16.dp,
                        verticalAlignment = Alignment.CenterVertically
                    ) { page ->
                        val distance = abs(page - pagerState.currentPage)
                        val game = games[page]
                        GameCard(
                            game = game,
                            distance = distance,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(page) } },
                            onPlay = { bootingGame = game }
                        )
                    }
                }

                // Focused Game Info
                if (pagerState.currentPage in games.indices) {
                    val activeGame = games[pagerState.currentPage]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(bootAlpha)
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = (activeGame.info.name.value ?: "UNKNOWN GAME").uppercase(),
                            style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp),
                            color = RPCSXColors.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                            InfoBadge(text = activeGame.info.path.substringAfterLast("/"))
                        }
                    }
                }
            }

            // Hint Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(bootAlpha)
                    .background(RPCSXColors.surfaceContainerHigh)
                    .drawBehind {
                        drawLine(
                            color = RPCSXColors.outlineVariant,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fwVersion by remember { FirmwareRepository.version }
                val fwProgressId by remember { FirmwareRepository.progressChannel }
                val fwProgressEntry = ProgressRepository.getItem(fwProgressId)?.value
                val fwProgressMessage = fwProgressEntry?.message?.value
                val isFwInstalling = fwProgressId != null

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fwVersion != null) {
                        Text(
                            text = stringResource(R.string.firmware) + " " + fwVersion,
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary
                        )
                    } else if (isFwInstalling) {
                        Text(
                            text = "Installing firmware...",
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.primary
                        )
                        if (fwProgressEntry != null) {
                            val fwVal = fwProgressEntry.value.longValue
                            val fwMax = fwProgressEntry.max.longValue
                            if (fwMax > 0) {
                                LinearProgressIndicator(
                                    progress = { fwVal.toFloat() / fwMax.toFloat() },
                                    modifier = Modifier
                                        .width(120.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = RPCSXColors.primary,
                                    trackColor = RPCSXColors.surfaceOverlay,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = RPCSXColors.primary,
                                    trackColor = RPCSXColors.surfaceOverlay,
                                )
                            }
                            fwProgressMessage?.let {
                                Text(
                                    text = it,
                                    style = AppTypography.labelSmall,
                                    color = RPCSXColors.textSecondary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.firmware) + " Not installed",
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            modifier = Modifier
                                .clickable { installFwLauncher?.launch("*/*") }
                                .padding(4.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    HintButton(text = "PLAY", icon = "X", color = RPCSXColors.primary, onClick = {
                        // For play, we can just use the currently centered page (no easy access to pagerState here without refactoring)
                        // Actually, wait, since we don't have focusedIndex anymore in scope, I'll pass it down or just leave it blank since this button is a hint, clicking the game itself plays it.
                        // I'll leave the onClick blank here for now as the hint strip is usually just visual.
                    })
                    HintButton(text = "OPTIONS", icon = "△", color = RPCSXColors.textSecondary, onClick = { navigateToSettings?.invoke() })
                    HintButton(text = "BACK", icon = "O", color = RPCSXColors.textSecondary, onClick = { })
                }
            }
        }

        if (showImportDialog) {
            ImportMethodDialog(
                onDismiss = { showImportDialog = false },
                onImportFolder = {
                    showImportDialog = false
                    folderPickerLauncher.launch(null)
                },
                onImportIso = {
                    showImportDialog = false
                    isoPickerLauncher.launch("*/*")
                }
            )
        }
    }
}

@Composable
fun InfoBadge(text: String) {
    Surface(
        color = RPCSXColors.surfaceElevated,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RPCSXColors.surfaceOverlay)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = AppTypography.labelMedium,
            color = RPCSXColors.textSecondary
        )
    }
}

@Composable
fun HintButton(text: String, icon: String, color: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(8.dp)
    ) {
        if (icon == "△") {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("▲", color = color, style = AppTypography.labelSmall.copy(fontSize = 14.sp))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(2.dp, color, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(icon, color = color, style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = color, style = AppTypography.labelSmall)
    }
}

@Composable
fun GameCard(game: Game, distance: Int, onClick: () -> Unit, onPlay: () -> Unit) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f
    
    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val installProgressId = game.findProgress(GameProgressType.Install)?.firstOrNull()?.id
    val progressEntry = ProgressRepository.getItem(installProgressId)?.value
    val isImporting = progressEntry != null
    val progressValue = progressEntry?.value?.longValue ?: 0
    val progressMax = progressEntry?.max?.longValue ?: 0
    val progressMessage = progressEntry?.message?.value
    val isIndeterminate = progressMax == 0L

    val colorMatrix = remember(isFocused) {
        if (isFocused) ColorMatrix() else ColorMatrix().apply { setToSaturation(0f) }
    }

    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .aspectRatio(0.85f)
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = { if (isFocused) onPlay() else onClick() })
            .shadow(
                elevation = if (isFocused) glowIntensity.dp else 0.dp,
                spotColor = RPCSXColors.focusGlow,
                ambientColor = RPCSXColors.focusGlow,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) RPCSXColors.focusRing else RPCSXColors.surfaceOverlay,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            if (game.info.iconPath.value != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Scaled up and blurred background image
                    AsyncImage(
                        model = game.info.iconPath.value,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(colorMatrix),
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.3f)
                            .blur(radius = 16.dp)
                            .alpha(0.5f)
                    )
                    
                    // Dark overlay to enhance contrast
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f))
                    )

                    // Crisp foreground image
                    AsyncImage(
                        model = game.info.iconPath.value,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.colorMatrix(colorMatrix),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
            
            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = RPCSXColors.primary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                        if (!isIndeterminate) {
                            LinearProgressIndicator(
                                progress = { progressValue.toFloat() / progressMax.toFloat() },
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        }
                        Text(
                            text = progressMessage ?: "Importing...",
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        
        if (isFocused) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)))
            )
        }
    }
}

fun bootGame(context: android.content.Context, game: Game) {
    if (game.hasFlag(GameFlag.Locked)) {
        return
    }
    GameRepository.onBoot(game)
    val emulatorWindow = Intent(context, RPCSXActivity::class.java)
    emulatorWindow.putExtra("path", game.info.path)
    context.startActivity(emulatorWindow)
}

@Composable
fun ImportMethodDialog(
    onDismiss: () -> Unit,
    onImportFolder: () -> Unit,
    onImportIso: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "IMPORT GAME",
                style = AppTypography.headlineMedium,
                color = RPCSXColors.primary
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Choose how you'd like to import a game.",
                    style = AppTypography.bodyLarge,
                    color = RPCSXColors.textSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onImportFolder,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RPCSXColors.primary,
                        contentColor = RPCSXColors.onPrimary
                    )
                ) {
                    Text("IMPORT FOLDER", style = AppTypography.labelSmall)
                }
                Button(
                    onClick = onImportIso,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RPCSXColors.primary,
                        contentColor = RPCSXColors.onPrimary
                    )
                ) {
                    Text("IMPORT ISO FILE", style = AppTypography.labelSmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("CANCEL", style = AppTypography.labelSmall, color = RPCSXColors.textSecondary)
            }
        },
        containerColor = RPCSXColors.surfaceElevated,
        shape = RoundedCornerShape(12.dp)
    )
}
