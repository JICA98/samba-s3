package com.zenithblue.sambas3.ui.games.launch

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
import com.zenithblue.sambas3.ui.ingame.SaveSlot
import com.zenithblue.sambas3.ui.ingame.TrophiesData
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class LaunchFocusTarget {
    START,
    CONTINUE,
    CONFIG,
    DRIVER,
    PATCHES,
    TROPHIES,
    CLEAR_CACHE,
    SAVES,
    CLOSE
}

@Composable
fun GameLaunchCenter(
    snapshot: GameLaunchSnapshot,
    onDismiss: () -> Unit,
    onFreshPlay: () -> Unit,
    onContinue: (SaveSlot) -> Unit,
    onLoad: (SaveSlot) -> Unit,
    onConfigure: () -> Unit,
    onDriver: () -> Unit,
    onPatches: () -> Unit,
    onAchievements: () -> Unit,
    onClearCache: () -> Unit,
    canClearCache: Boolean,
    onPrepare: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    showTrophies: Boolean = false,
    trophiesData: TrophiesData? = null,
    trophiesLoading: Boolean = false,
    onDismissTrophies: () -> Unit = {},
) {
    val context = LocalContext.current
    val rawIconPath = snapshot.game.info.iconPath.value
    val installedPreview = remember(rawIconPath) {
        GamePreviewRepository.resolveInstalledPreview(rawIconPath)
    }
    val coilModel: Any? = when (installedPreview) {
        is GamePreviewModel.LocalFile -> installedPreview.file
        is GamePreviewModel.ContentUri -> installedPreview.uri
        is GamePreviewModel.None -> null
    }

    val titleId = remember(snapshot) {
        snapshot.titleId ?: snapshot.game.info.path.substringAfterLast('/')
    }
    val bgPreview by produceState<Any?>(
        initialValue = null,
        key1 = snapshot.game.info.path,
        key2 = rawIconPath
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (titleId.isNotBlank()) {
                val directIsoPic1 = File(context.filesDir, "direct_iso_icons/${titleId}_pic1.png")
                if (directIsoPic1.isFile && directIsoPic1.length() > 0) {
                    return@withContext directIsoPic1
                }
            }
            when (val bg = GamePreviewRepository.resolveBackground(context, snapshot.game)) {
                is GamePreviewModel.LocalFile -> if (bg.file.exists() && bg.file.length() > 0) bg.file else null
                is GamePreviewModel.ContentUri -> bg.uri
                is GamePreviewModel.None -> null
            }
        }
    }
    val previewBgModel: Any = bgPreview ?: R.drawable.default_wallpaper

    val ppuUi = snapshot.ppuUi
    val enabledPatches by produceState(emptyList<String>(), titleId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.zenithblue.sambas3.PatchRepository.forTitle(
                com.zenithblue.sambas3.PatchRepository.list(), titleId,
            ).filter { it.enabled }.map { it.name }.distinct()
        }
    }
    val existingSaves = snapshot.saveSlots.filter { it.exists }
    val hasContinue = snapshot.latestSave != null && existingSaves.isNotEmpty()

    var focusedTarget by remember { mutableStateOf(LaunchFocusTarget.START) }
    var focusedSaveIndex by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    BackHandler(enabled = !showTrophies) {
        onDismiss()
    }

    fun dismissTrophiesOrLauncher() {
        if (showTrophies) onDismissTrophies() else onDismiss()
    }

    fun triggerAction() {
        if (showTrophies) return
        when (focusedTarget) {
            LaunchFocusTarget.START -> {
                if (ppuUi.prepareAction == PrepareAction.Prepare || ppuUi.prepareAction == PrepareAction.Retry) {
                    onPrepare?.invoke()
                } else if (ppuUi.prepareAction == PrepareAction.Stop) {
                    onStop?.invoke()
                } else if (ppuUi.prepareAction == PrepareAction.Stopping) {
                    // Idempotent no-op while the owner waits for verified worker exit.
                } else if (ppuUi.startEnabled && snapshot.canPlayFresh) {
                    onFreshPlay()
                }
            }
            LaunchFocusTarget.CONTINUE -> {
                snapshot.latestSave?.let { slot ->
                    if (snapshot.canLoadSave) onContinue(slot)
                }
            }
            LaunchFocusTarget.CONFIG -> onConfigure()
            LaunchFocusTarget.DRIVER -> onDriver()
            LaunchFocusTarget.PATCHES -> onPatches()
            LaunchFocusTarget.TROPHIES -> onAchievements()
            LaunchFocusTarget.CLEAR_CACHE -> if (canClearCache) onClearCache()
            LaunchFocusTarget.SAVES -> existingSaves.getOrNull(focusedSaveIndex)?.let { if (snapshot.canLoadSave) onLoad(it) }
            LaunchFocusTarget.CLOSE -> onDismiss()
        }
    }

    fun onNavigateUp() {
        if (showTrophies) return
        when (focusedTarget) {
            LaunchFocusTarget.START, LaunchFocusTarget.CONTINUE -> focusedTarget = LaunchFocusTarget.CLOSE
            LaunchFocusTarget.PATCHES -> focusedTarget = LaunchFocusTarget.CONFIG
            LaunchFocusTarget.TROPHIES -> focusedTarget = LaunchFocusTarget.DRIVER
            LaunchFocusTarget.CLEAR_CACHE -> focusedTarget = LaunchFocusTarget.PATCHES
            LaunchFocusTarget.SAVES -> focusedTarget = LaunchFocusTarget.CLEAR_CACHE
            LaunchFocusTarget.DRIVER, LaunchFocusTarget.CONFIG -> focusedTarget = LaunchFocusTarget.CLOSE
            LaunchFocusTarget.CLOSE -> {}
        }
    }

    fun onNavigateDown() {
        if (showTrophies) return
        when (focusedTarget) {
            LaunchFocusTarget.CLOSE -> focusedTarget = LaunchFocusTarget.START
            LaunchFocusTarget.CONFIG -> focusedTarget = LaunchFocusTarget.PATCHES
            LaunchFocusTarget.DRIVER -> focusedTarget = LaunchFocusTarget.TROPHIES
            LaunchFocusTarget.PATCHES, LaunchFocusTarget.TROPHIES -> focusedTarget = LaunchFocusTarget.CLEAR_CACHE
            LaunchFocusTarget.CLEAR_CACHE -> focusedTarget = if (existingSaves.isNotEmpty()) LaunchFocusTarget.SAVES else if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.START
            LaunchFocusTarget.SAVES -> focusedTarget = if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.START
            LaunchFocusTarget.START, LaunchFocusTarget.CONTINUE -> {}
        }
    }

    fun onNavigateLeft() {
        if (showTrophies) return
        when (focusedTarget) {
            LaunchFocusTarget.START -> focusedTarget = if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.TROPHIES
            LaunchFocusTarget.CONTINUE -> focusedTarget = LaunchFocusTarget.TROPHIES
            LaunchFocusTarget.TROPHIES -> focusedTarget = LaunchFocusTarget.PATCHES
            LaunchFocusTarget.CLEAR_CACHE -> focusedTarget = LaunchFocusTarget.PATCHES
            LaunchFocusTarget.DRIVER -> focusedTarget = LaunchFocusTarget.CONFIG
            LaunchFocusTarget.CLOSE -> focusedTarget = LaunchFocusTarget.CONFIG
            LaunchFocusTarget.SAVES -> focusedSaveIndex = (focusedSaveIndex - 1).coerceAtLeast(0)
            LaunchFocusTarget.CONFIG, LaunchFocusTarget.PATCHES -> {}
        }
    }

    fun onNavigateRight() {
        if (showTrophies) return
        when (focusedTarget) {
            LaunchFocusTarget.CONFIG -> focusedTarget = LaunchFocusTarget.DRIVER
            LaunchFocusTarget.PATCHES -> focusedTarget = LaunchFocusTarget.TROPHIES
            LaunchFocusTarget.DRIVER -> focusedTarget = if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.START
            LaunchFocusTarget.TROPHIES -> focusedTarget = if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.START
            LaunchFocusTarget.CLEAR_CACHE -> focusedTarget = if (hasContinue) LaunchFocusTarget.CONTINUE else LaunchFocusTarget.START
            LaunchFocusTarget.CONTINUE -> focusedTarget = LaunchFocusTarget.START
            LaunchFocusTarget.SAVES -> focusedSaveIndex = (focusedSaveIndex + 1).coerceAtMost(existingSaves.lastIndex)
            LaunchFocusTarget.START, LaunchFocusTarget.CLOSE -> {}
        }
    }

    val currentView = LocalView.current
    var stickStateX by remember { mutableIntStateOf(0) }
    var stickStateY by remember { mutableIntStateOf(0) }
    var lastStickTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(currentView, showTrophies) {
        val motionListener = View.OnGenericMotionListener { _, event ->
            val source = event.source
            val isGamepadOrJoystick = (source and InputDevice.SOURCE_GAMEPAD != 0) ||
                (source and InputDevice.SOURCE_JOYSTICK != 0)
            if (!isGamepadOrJoystick) return@OnGenericMotionListener false

            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val x = if (abs(rawX) > 0.05f) rawX else hatX

            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val y = if (abs(rawY) > 0.05f) rawY else hatY

            val now = android.os.SystemClock.uptimeMillis()

            if (abs(x) > abs(y)) {
                when {
                    x < -0.45f -> {
                        if (stickStateX != -1) {
                            stickStateX = -1
                            lastStickTime = now
                            onNavigateLeft()
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            onNavigateLeft()
                            true
                        } else true
                    }
                    x > 0.45f -> {
                        if (stickStateX != 1) {
                            stickStateX = 1
                            lastStickTime = now
                            onNavigateRight()
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            onNavigateRight()
                            true
                        } else true
                    }
                    abs(x) < 0.25f -> {
                        stickStateX = 0
                        false
                    }
                    else -> false
                }
            } else {
                when {
                    y < -0.45f -> {
                        if (stickStateY != -1) {
                            stickStateY = -1
                            lastStickTime = now
                            onNavigateUp()
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            onNavigateUp()
                            true
                        } else true
                    }
                    y > 0.45f -> {
                        if (stickStateY != 1) {
                            stickStateY = 1
                            lastStickTime = now
                            onNavigateDown()
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            onNavigateDown()
                            true
                        } else true
                    }
                    abs(y) < 0.25f -> {
                        stickStateY = 0
                        false
                    }
                    else -> false
                }
            }
        }

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { onNavigateUp(); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { onNavigateDown(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { onNavigateLeft(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onNavigateRight(); true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    triggerAction()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    dismissTrophiesOrLauncher()
                    true
                }
                else -> false
            }
        }

        currentView.setOnGenericMotionListener(motionListener)
        currentView.setOnKeyListener(keyListener)
        onDispose {
            currentView.setOnGenericMotionListener(null)
            currentView.setOnKeyListener(null)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .45f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth(.92f)
                .fillMaxHeight(.90f)
                .widthIn(max = 920.dp)
                .padding(4.dp)
                .navigationBarsPadding()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val code = keyEvent.nativeKeyEvent.keyCode
                    when {
                        keyEvent.key == Key.DirectionUp || code == KeyEvent.KEYCODE_DPAD_UP -> { onNavigateUp(); true }
                        keyEvent.key == Key.DirectionDown || code == KeyEvent.KEYCODE_DPAD_DOWN -> { onNavigateDown(); true }
                        keyEvent.key == Key.DirectionLeft || code == KeyEvent.KEYCODE_DPAD_LEFT -> { onNavigateLeft(); true }
                        keyEvent.key == Key.DirectionRight || code == KeyEvent.KEYCODE_DPAD_RIGHT -> { onNavigateRight(); true }
                        keyEvent.key == Key.ButtonA || keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
                        code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> {
                            triggerAction()
                            true
                        }
                        keyEvent.key == Key.ButtonB || keyEvent.key == Key.Back ||
                        code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK -> {
                            dismissTrophiesOrLauncher()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Box(Modifier.fillMaxSize()) {
                // Frosted glass background layer
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF20E1626),
                                    Color(0xF8080C14)
                                )
                            )
                        )
                )

                // Ambient blurred glow orbs (glassmorphism blur without external image)
                Box(
                    modifier = Modifier
                        .size(320.dp)
                        .offset(x = (-40).dp, y = (-30).dp)
                        .blur(60.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    RPCSXColors.primary.copy(alpha = 0.18f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(340.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 60.dp, y = 40.dp)
                        .blur(70.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0x301A3660),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // LEFT PANE: Game Card, Title, ID, and Secondary Action Buttons
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            // Game Artwork Card (matching home screen GameCard style)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Black.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, RPCSXColors.surfaceOverlay),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(10.dp)),
                            ) {
                                if (coilModel != null || bgPreview != null) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        // Blurred ambient background (PIC1.PNG artwork or default_wallpaper fallback)
                                        AsyncImage(
                                            model = previewBgModel,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .scale(1.15f)
                                                .blur(radius = 16.dp)
                                                .alpha(if (bgPreview != null) 0.65f else 0.50f),
                                        )
                                        // Dark contrast overlay
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = if (bgPreview != null) 0.35f else 0.30f)),
                                        )
                                        // Crisp foreground artwork
                                        if (coilModel != null) {
                                            AsyncImage(
                                                model = coilModel,
                                                contentDescription = "Game cover",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(6.dp),
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(RPCSXColors.surfaceOverlay),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.gamepad),
                                            contentDescription = null,
                                            tint = RPCSXColors.textSecondary,
                                            modifier = Modifier.size(44.dp),
                                        )
                                    }
                                }
                            }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            GameIdentity.displayName(
                                snapshot.game.info.path,
                                snapshot.game.info.name.value,
                            ).uppercase(),
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            snapshot.titleId ?: snapshot.game.info.path.substringAfterLast('/'),
                            color = RPCSXColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // Quick action buttons at bottom of left pane
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val isConfigFocused = focusedTarget == LaunchFocusTarget.CONFIG
                            OutlinedButton(
                                onClick = onConfigure,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isConfigFocused) 2.dp else 1.dp,
                                    if (isConfigFocused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isConfigFocused) RPCSXColors.primaryMuted else Color.Transparent,
                                    contentColor = if (isConfigFocused) RPCSXColors.primary else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("CONFIG", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }

                            val isDriverFocused = focusedTarget == LaunchFocusTarget.DRIVER
                            OutlinedButton(
                                onClick = onDriver,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isDriverFocused) 2.dp else 1.dp,
                                    if (isDriverFocused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isDriverFocused) RPCSXColors.primaryMuted else Color.Transparent,
                                    contentColor = if (isDriverFocused) RPCSXColors.primary else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("DRIVER", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val isPatchesFocused = focusedTarget == LaunchFocusTarget.PATCHES
                            OutlinedButton(
                                onClick = onPatches,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isPatchesFocused) 2.dp else 1.dp,
                                    if (isPatchesFocused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isPatchesFocused) RPCSXColors.primaryMuted else Color.Transparent,
                                    contentColor = if (isPatchesFocused) RPCSXColors.primary else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("PATCHES (${enabledPatches.size})", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }

                            val isTrophiesFocused = focusedTarget == LaunchFocusTarget.TROPHIES
                            OutlinedButton(
                                onClick = onAchievements,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    if (isTrophiesFocused) 2.dp else 1.dp,
                                    if (isTrophiesFocused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isTrophiesFocused) RPCSXColors.primaryMuted else Color.Transparent,
                                    contentColor = if (isTrophiesFocused) RPCSXColors.primary else MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("TROPHIES", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        val isClearCacheFocused = focusedTarget == LaunchFocusTarget.CLEAR_CACHE
                        OutlinedButton(
                            onClick = onClearCache,
                            enabled = canClearCache,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                if (isClearCacheFocused) 2.dp else 1.dp,
                                if (isClearCacheFocused) RPCSXColors.focusRing else RPCSXColors.errorColor.copy(alpha = 0.7f),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isClearCacheFocused) RPCSXColors.errorColor.copy(alpha = 0.12f) else Color.Transparent,
                                contentColor = RPCSXColors.errorColor,
                            ),
                        ) {
                            Text("CLEAR CACHE", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }

                // VERTICAL DIVIDER
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )

                // RIGHT PANE: Header, Settings (2-col), PPU, Saves, and Footer Actions
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    // Top Header Row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "LAUNCH PROFILE",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        val isCloseFocused = focusedTarget == LaunchFocusTarget.CLOSE
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            border = if (isCloseFocused) BorderStroke(1.5.dp, RPCSXColors.focusRing) else null,
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (isCloseFocused) RPCSXColors.primaryMuted else Color.Transparent,
                                contentColor = RPCSXColors.primary
                            )
                        ) {
                            Text("CLOSE", color = RPCSXColors.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )

                    // Scrollable Middle Body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Settings split into 2 compact columns
                        val allSettings = buildList {
                            addAll(snapshot.settings)
                            add(
                                LaunchSetting(
                                    label = "GPU driver",
                                    value = snapshot.selectedDriver + if (snapshot.driverSysmem) " (SYSMEM)" else "",
                                    source = "",
                                )
                            )
                        }
                        val mid = (allSettings.size + 1) / 2
                        val col1 = allSettings.take(mid)
                        val col2 = allSettings.drop(mid)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                col1.forEach { setting -> SettingRow(setting) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                col2.forEach { setting -> SettingRow(setting) }
                            }
                        }

                        // PPU Preparation
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "PPU PREPARATION",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        PpuPhaseRow(ppuUi.installPpu)
                        PpuPhaseRow(ppuUi.runtimePpu)
                        if (enabledPatches.isNotEmpty()) {
                            Text("ENABLED PATCHES", color = RPCSXColors.primary, style = MaterialTheme.typography.labelLarge)
                            enabledPatches.forEach { name ->
                                Text(name, color = RPCSXColors.textPrimary, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Saves
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "SAVES",
                            color = RPCSXColors.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (existingSaves.isEmpty()) {
                            Text(
                                "No saved states yet",
                                color = RPCSXColors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                existingSaves.forEachIndexed { index, slot ->
                                    LaunchSaveCard(
                                        slot,
                                        enabled = snapshot.canLoadSave,
                                        focused = focusedTarget == LaunchFocusTarget.SAVES && focusedSaveIndex == index,
                                        onClick = { onLoad(slot) },
                                    )
                                }
                            }
                        }
                    }

                    // Fixed Footer
                    HorizontalDivider(
                        Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val footerStatus = snapshot.blockReason ?: ppuUi.statusLine
                        // Controller Navigation Hints — wrap onto extra rows instead of crushing
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = RPCSXColors.textSecondary.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, RPCSXColors.textSecondary)
                                ) {
                                    Text(
                                        "D-PAD",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = RPCSXColors.textSecondary
                                    )
                                }
                                Text("NAVIGATE", style = MaterialTheme.typography.labelSmall, color = RPCSXColors.textSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = RPCSXColors.primary.copy(alpha = 0.22f),
                                    border = BorderStroke(1.dp, RPCSXColors.primary)
                                ) {
                                    Text(
                                        "✕",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = RPCSXColors.primary
                                    )
                                }
                                Text("CONFIRM", style = MaterialTheme.typography.labelSmall, color = RPCSXColors.textSecondary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = RPCSXColors.textSecondary.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, RPCSXColors.textSecondary)
                                ) {
                                    Text(
                                        "○",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = RPCSXColors.textSecondary
                                    )
                                }
                                Text("BACK", style = MaterialTheme.typography.labelSmall, color = RPCSXColors.textSecondary)
                            }

                            if (footerStatus != null) {
                                Text(
                                    footerStatus.uppercase(),
                                    color = if (snapshot.blockReason != null) RPCSXColors.errorColor else RPCSXColors.textSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val isStartFocused = focusedTarget == LaunchFocusTarget.START
                            when (ppuUi.prepareAction) {
                                PrepareAction.Prepare -> {
                                    if (onPrepare != null) {
                                        OutlinedButton(
                                            onClick = onPrepare,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(
                                                if (isStartFocused) 2.dp else 1.dp,
                                                if (isStartFocused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant
                                            ),
                                        ) { Text("✕ PREPARE PPU", style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                                PrepareAction.Retry -> {
                                    if (onPrepare != null) {
                                        OutlinedButton(
                                            onClick = onPrepare,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = RPCSXColors.errorColor,
                                            ),
                                            border = BorderStroke(
                                                if (isStartFocused) 2.dp else 1.dp,
                                                if (isStartFocused) RPCSXColors.focusRing else RPCSXColors.errorColor.copy(alpha = 0.7f)
                                            ),
                                        ) { Text("✕ RETRY PPU", style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                                PrepareAction.PreparingInstall -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text("PREPARING PPU…", style = MaterialTheme.typography.labelMedium) }
                                }
                                PrepareAction.PreparingRuntime -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text("PREPARING RUNTIME PPU…", style = MaterialTheme.typography.labelMedium) }
                                }
                                PrepareAction.Stop -> {
                                    if (onStop != null) {
                                        OutlinedButton(
                                            onClick = onStop,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = RPCSXColors.errorColor,
                                            ),
                                            border = BorderStroke(
                                                if (isStartFocused) 2.dp else 1.dp,
                                                if (isStartFocused) RPCSXColors.focusRing else RPCSXColors.errorColor.copy(alpha = 0.7f)
                                            ),
                                        ) { Text("STOP PPU", style = MaterialTheme.typography.labelMedium) }
                                    }
                                }
                                PrepareAction.Stopping -> {
                                    OutlinedButton(
                                        onClick = { onStop?.invoke() },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = RPCSXColors.errorColor,
                                        ),
                                    ) { Text("STOPPING PPU…", style = MaterialTheme.typography.labelMedium) }
                                }
                                PrepareAction.Locked -> {
                                    OutlinedButton(
                                        onClick = {},
                                        enabled = false,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text("WAITING — PPU BUSY", style = MaterialTheme.typography.labelMedium) }
                                }
                                null -> Unit
                            }
                            snapshot.latestSave?.let { slot ->
                                if (existingSaves.isNotEmpty()) {
                                    val isContinueFocused = focusedTarget == LaunchFocusTarget.CONTINUE
                                    Button(
                                        onClick = { onContinue(slot) },
                                        enabled = snapshot.canLoadSave,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = RPCSXColors.surfaceOverlay,
                                            contentColor = if (isContinueFocused) RPCSXColors.focusRing else RPCSXColors.textPrimary,
                                        ),
                                        border = if (isContinueFocused) BorderStroke(2.dp, RPCSXColors.focusRing) else null,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("✕ CONTINUE ${slot.slot}", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            val showStart = ppuUi.prepareAction != PrepareAction.Prepare &&
                                ppuUi.prepareAction != PrepareAction.Retry &&
                                ppuUi.prepareAction != PrepareAction.Stop
                            if (showStart) {
                                Button(
                                    onClick = onFreshPlay,
                                    enabled = ppuUi.startEnabled && snapshot.canPlayFresh,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isStartFocused) RPCSXColors.focusRing else RPCSXColors.primary,
                                        contentColor = Color.Black,
                                        disabledContainerColor = RPCSXColors.primary.copy(alpha = 0.35f),
                                        disabledContentColor = Color.Black.copy(alpha = 0.35f),
                                    ),
                                    border = if (isStartFocused) BorderStroke(2.dp, RPCSXColors.focusRing) else null,
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        "START",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.gamepad),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
    if (showTrophies) {
        StoppedTrophiesOverlay(
            data = trophiesData,
            loading = trophiesLoading,
            onDismiss = onDismissTrophies,
        )
    }
    }
}

@Composable
private fun SettingRow(setting: LaunchSetting) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            setting.label,
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                setting.value,
                color = RPCSXColors.textPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (setting.source.isNotBlank()) {
                Text(
                    setting.source,
                    color = if (setting.source == "GAME") RPCSXColors.primary else RPCSXColors.textSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PpuPhaseRow(phase: PpuPhaseUi) {
    val color = when (phase.state) {
        PpuPhaseState.Ready -> RPCSXColors.primary
        PpuPhaseState.Failed -> RPCSXColors.errorColor
        PpuPhaseState.Compiling, PpuPhaseState.Preparing, PpuPhaseState.Finalizing -> RPCSXColors.textPrimary
        else -> RPCSXColors.textSecondary
    }
    val statusText = LaunchPpuPresentation.phaseStatusText(phase)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(phase.label, color = RPCSXColors.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(statusText, color = color, style = MaterialTheme.typography.bodySmall)
        }
        if (phase.state == PpuPhaseState.Compiling) {
            val pct = phase.progress
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pct != null && pct > 0) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                        color = RPCSXColors.primary,
                        trackColor = RPCSXColors.surfaceOverlay,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.weight(1f),
                        color = RPCSXColors.primary,
                        trackColor = RPCSXColors.surfaceOverlay,
                    )
                }
                if (!phase.remainingLabel.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        phase.remainingLabel,
                        color = RPCSXColors.textSecondary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchSaveCard(slot: SaveSlot, enabled: Boolean, focused: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = RPCSXColors.surface),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) RPCSXColors.focusRing else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(72.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(RPCSXColors.surfaceOverlay),
                contentAlignment = Alignment.Center,
            ) {
                if (slot.previewPath != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(slot.previewPath))
                            .memoryCacheKey("${slot.previewPath}:${slot.previewMtimeMs}")
                            .build(),
                        contentDescription = "Saved game preview for Slot ${slot.slot}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painterResource(R.drawable.ic_save),
                        "Slot ${slot.slot} placeholder",
                        tint = RPCSXColors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text("SLOT ${slot.slot}", color = RPCSXColors.textPrimary, style = MaterialTheme.typography.bodySmall)
                Text(
                    formatLaunchSlotTime(slot.mtimeMs),
                    color = RPCSXColors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatLaunchSlotTime(mtimeMs: Long): String =
    if (mtimeMs <= 0L) "—"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(mtimeMs))
