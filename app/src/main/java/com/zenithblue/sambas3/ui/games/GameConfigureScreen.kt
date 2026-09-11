package com.zenithblue.sambas3.ui.games

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.AppTypography
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RPCSXColors
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec.SettingNodeSpec
import com.zenithblue.sambas3.input.ControllerDeviceRepository
import com.zenithblue.sambas3.input.InputDeviceType
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceIcon
import com.zenithblue.sambas3.ui.settings.components.core.PreferenceTitle
import com.zenithblue.sambas3.ui.settings.components.gamepadActivate
import com.zenithblue.sambas3.ui.settings.components.preference.RegularPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SingleSelectionDialog
import com.zenithblue.sambas3.ui.settings.components.preference.SliderPreference
import com.zenithblue.sambas3.ui.settings.components.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Curated per-title sections; every candidate node is resolved from the LIVE tree. */
private data class CuratedSection(
    val titleRes: Int,
    val iconRes: Int,
    val nodePaths: List<String>
)

private val CURATED_SECTIONS = listOf(
    CuratedSection(
        titleRes = R.string.ingame_section_video,
        iconRes = R.drawable.ic_video,
        nodePaths = listOf(
            "Video@@Resolution",
            "Video@@Aspect ratio",
            "Video@@Anisotropic Filter",
            "Video@@MSAA",
            "Video@@Shader Mode",
            "Video@@Frame limit",
            "Video@@Write Color Buffers",
            "Video@@Read Color Buffers",
            "Video@@VSync"
        )
    ),
    CuratedSection(
        titleRes = R.string.ingame_section_core,
        iconRes = R.drawable.memory,
        nodePaths = listOf(
            "Core@@Max LLVM Compile Threads",
            "Core@@PPU Decoder",
            "Core@@SPU Decoder",
            "Core@@SPU Block Size",
            "Core@@SPU Threads"
        )
    ),
    CuratedSection(
        titleRes = R.string.ingame_section_audio,
        iconRes = R.drawable.ic_audio,
        nodePaths = listOf(
            "Audio@@Master Volume",
            "Audio@@Buffer Duration",
            "Audio@@Enable Buffering"
        )
    )
)

private val GlassPanel = Color(0xCC16203A)
private val GlassHeader = Color(0xE60D1224)
private val GlassBorder = Color(0x38C9A84C)
private val GlassDivider = Color(0x33C9A84C)

/** Flat focus targets for controller navigation. */
private sealed interface ConfigTarget {
    data object Back : ConfigTarget
    data object Menu : ConfigTarget
    data class Row(val index: Int) : ConfigTarget
    data object Retry : ConfigTarget
}

private data class ConfigRowRef(
    val index: Int,
    val path: String,
    val node: JSONObject,
    val sectionIndex: Int,
    val listIndex: Int
)

private data class ConfigStructure(
    val sections: List<Pair<CuratedSection, List<ConfigRowRef>>>,
    val rows: List<ConfigRowRef>
)

private fun buildConfigStructure(tree: JSONObject?): ConfigStructure {
    val root = tree ?: return ConfigStructure(emptyList(), emptyList())
    val sections = mutableListOf<Pair<CuratedSection, List<ConfigRowRef>>>()
    val allRows = mutableListOf<ConfigRowRef>()
    var listIndex = 0
    CURATED_SECTIONS.forEachIndexed { sectionIndex, section ->
        val resolved = section.nodePaths.mapNotNull { path ->
            findCuratedNode(root, path)?.let { path to it }
        }
        if (resolved.isEmpty()) return@forEachIndexed
        listIndex++ // section header item
        val refs = resolved.map { (path, node) ->
            ConfigRowRef(allRows.size, path, node, sectionIndex, listIndex++).also { allRows += it }
        }
        sections += section to refs
    }
    return ConfigStructure(sections, allRows)
}

/**
 * Per-game Configure Game page: curated tri-state rows (Use Global vs Override),
 * per-row reset (long-click or the trailing restore action), Reset All overflow.
 * Values persist as sparse RPCS3 title overrides. The core loads them after the
 * canonical global config on the next emulation boot; this screen never writes a
 * title value through the global setter.
 *
 * Hosted either fullscreen inside the emulation overlay ([GameConfigureOverlay])
 * or fullscreen from the library (blurred backdrop Dialog). Controller input is
 * handled by this composable in both hosts.
 */
@Composable
fun GameConfigureScreen(
    gamePath: String?,
    modifier: Modifier = Modifier,
    isInGame: Boolean = false,
    onClose: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf<JSONObject?>(null) }
    var engineReady by remember { mutableStateOf(isInGame) }
    var gateRetryToken by remember { mutableIntStateOf(0) }

    // Engine gate (library host only): reading/editing the config tree requires a
    // live initialized engine that is NOT running a game. This is a read-only check.
    LaunchedEffect(isInGame, gateRetryToken) {
        if (isInGame) {
            engineReady = true
            return@LaunchedEffect
        }
        engineReady = runCatching {
            RPCSX.activeLibrary.value != null && RPCSX.getState() == EmulatorState.Stopped
        }.getOrDefault(false)
    }

    LaunchedEffect(engineReady, gateRetryToken) {
        if (!engineReady) {
            tree = null
            return@LaunchedEffect
        }
        tree = withContext(Dispatchers.IO) {
            try {
                JSONObject(RPCSX.instance.settingsGetGlobal(""))
            } catch (e: Exception) {
                null
            }
        }
    }

    val titleId = remember(tree, gamePath) {
        GameSettingsOverrides.resolveTitleId(gamePath ?: "", context)
            ?: if (isInGame) runCatching { RPCSX.instance.getTitleId() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            else null
    }

    var overrides by remember(titleId) {
        mutableStateOf(GameSettingsOverrides.gameOverrides(context, titleId ?: ""))
    }
    var showResetAllConfirm by remember { mutableStateOf(false) }
    var resetAllMenuOpen by remember { mutableStateOf(false) }

    fun refreshOverrides() {
        overrides = GameSettingsOverrides.gameOverrides(context, titleId ?: "")
    }

    fun commitOverride(path: String, node: JSONObject, newDisplayValue: String) {
        val tid = titleId ?: return
        val spec = nodeSpec(node)
        val encoded = SettingsValueCodec.encodedFromNode(spec, newDisplayValue)
        val previousEncoded = overrides[path] ?: engineEncodedValue(node)
        val appliedLive = !isInGame || path != FRAME_LIMIT_PATH || runCatching {
            RPCSX.instance.settingsSetTransient(path, encoded)
        }.getOrDefault(false)
        if (!appliedLive) {
            Log.w("S3FPS", "live limiter rejected title=$tid path=$path requested=$encoded")
            AlertDialogQueue.showDialog(
                context.getString(R.string.error),
                context.getString(R.string.failed_to_assign_value, newDisplayValue, path)
            )
            return
        }
        val applied = GameSettingsOverrides.recordGame(
            context = context,
            titleId = tid,
            path = path,
            encoded = encoded,
            previousEncoded = previousEncoded
        )
        if (!applied) {
            if (isInGame && path == FRAME_LIMIT_PATH) {
                runCatching { RPCSX.instance.settingsSetTransient(path, previousEncoded) }
            }
            AlertDialogQueue.showDialog(
                context.getString(R.string.error),
                context.getString(R.string.failed_to_assign_value, newDisplayValue, path)
            )
            return
        }
        if (path == FRAME_LIMIT_PATH) {
            Log.i(
                "S3FPS",
                "limiter committed title=$tid mode=$newDisplayValue live=${if (isInGame) 1 else 0} persisted=1"
            )
        }
        refreshOverrides()
    }

    fun resetRow(path: String, node: JSONObject) {
        val tid = titleId ?: return
        val fallback = SettingsValueCodec.encodedDefault(nodeSpec(node))
            ?: engineEncodedValue(node)
        if (GameSettingsOverrides.clearGameSetting(context, tid, path, fallback)) {
            refreshOverrides()
        } else {
            AlertDialogQueue.showDialog(
                context.getString(R.string.error),
                context.getString(R.string.failed_to_reset_key, path)
            )
        }
    }

    val showGate = !isInGame && !engineReady
    val menuAvailable = titleId != null || (onRemove != null && !isInGame)
    val curated = remember(tree) { buildConfigStructure(tree) }
    val rowRefs = curated.rows

    val targets = remember(onClose, menuAvailable, showGate, rowRefs) {
        buildList {
            if (onClose != null) add(ConfigTarget.Back)
            if (menuAvailable) add(ConfigTarget.Menu)
            rowRefs.forEach { add(ConfigTarget.Row(it.index)) }
            if (showGate) add(ConfigTarget.Retry)
        }
    }

    val requesters = remember { mutableMapOf<ConfigTarget, FocusRequester>() }
    fun requesterFor(target: ConfigTarget): FocusRequester =
        requesters.getOrPut(target) { FocusRequester() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var focusedTarget by remember { mutableStateOf<ConfigTarget?>(null) }
    var focusedSection by remember { mutableIntStateOf(0) }
    var lastMoveRepeatAt by remember { mutableLongStateOf(0L) }
    val rootRequester = remember { FocusRequester() }

    fun focusTarget(target: ConfigTarget) {
        focusedTarget = target
        if (target is ConfigTarget.Row) {
            rowRefs.getOrNull(target.index)?.let { focusedSection = it.sectionIndex }
        }
        scope.launch {
            if (target is ConfigTarget.Row) {
                rowRefs.getOrNull(target.index)?.let { ref ->
                    runCatching { listState.animateScrollToItem(ref.listIndex) }
                }
            }
            runCatching { requesterFor(target).requestFocus() }
        }
    }

    fun moveFocus(delta: Int, fromRepeat: Boolean) {
        if (targets.isEmpty()) return
        if (fromRepeat) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveRepeatAt < 130L) return
            lastMoveRepeatAt = now
        }
        val current = focusedTarget?.let { targets.indexOf(it) } ?: -1
        focusTarget(targets[ConfigFocusNavigator.move(current, delta, targets.size)])
    }

    fun jumpSection(delta: Int) {
        if (rowRefs.isEmpty()) return
        val sectionCount = rowRefs.maxOf { it.sectionIndex } + 1
        val nextSection = ConfigFocusNavigator.sectionJump(focusedSection, delta, sectionCount)
        val firstRow = rowRefs.indexOfFirst { it.sectionIndex == nextSection }
        if (firstRow >= 0) focusTarget(ConfigTarget.Row(firstRow))
    }

    fun scrollPage(delta: Int) {
        scope.launch {
            val viewport = listState.layoutInfo.viewportSize.height.toFloat()
            if (viewport > 0f) {
                runCatching { listState.scrollBy(delta * viewport * 0.9f) }
            }
        }
    }

    fun focusedRow(): ConfigRowRef? {
        val row = focusedTarget as? ConfigTarget.Row ?: return null
        return rowRefs.getOrNull(row.index)
    }

    fun resetFocusedRow() {
        val ref = focusedRow() ?: return
        if (ref.path in overrides) resetRow(ref.path, ref.node)
    }

    fun openOverflow() {
        if (menuAvailable) resetAllMenuOpen = true
    }

    fun closePage() {
        onClose?.invoke()
    }

    fun handleKeyEvent(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val code = event.nativeKeyEvent.keyCode
        val repeat = event.nativeKeyEvent.repeatCount > 0
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveFocus(-1, repeat)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveFocus(1, repeat)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (!repeat) jumpSection(-1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (!repeat) jumpSection(1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (!repeat) scrollPage(-1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (!repeat) scrollPage(1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (!repeat) resetFocusedRow()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (!repeat) resetFocusedRow()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT -> {
                if (!repeat) openOverflow()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!repeat) closePage()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (repeat) return true
                return when (focusedTarget) {
                    ConfigTarget.Back -> {
                        closePage()
                        true
                    }
                    ConfigTarget.Menu -> {
                        openOverflow()
                        true
                    }
                    ConfigTarget.Retry -> {
                        gateRetryToken++
                        true
                    }
                    is ConfigTarget.Row -> false // row components handle activation
                    null -> false
                }
            }
            else -> return false
        }
    }

    val currentView = LocalView.current
    val moveFromMotion = rememberUpdatedState<(Int) -> Unit> { delta -> moveFocus(delta, fromRepeat = false) }
    DisposableEffect(currentView) {
        var stickStateX = 0
        var stickStateY = 0
        var lastStickTime = 0L
        val motionListener = View.OnGenericMotionListener { _, event ->
            val source = event.source
            val isGamepadOrJoystick = (source and InputDevice.SOURCE_GAMEPAD != 0) ||
                (source and InputDevice.SOURCE_JOYSTICK != 0)
            if (!isGamepadOrJoystick || event.action != MotionEvent.ACTION_MOVE) {
                return@OnGenericMotionListener false
            }
            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val x = if (abs(rawX) > 0.05f) rawX else hatX
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val y = if (abs(rawY) > 0.05f) rawY else hatY
            val now = SystemClock.uptimeMillis()

            if (abs(x) > abs(y)) {
                return@OnGenericMotionListener when {
                    x < -0.45f -> {
                        if (stickStateX != -1) {
                            stickStateX = -1
                            lastStickTime = now
                            moveFromMotion.value(-1)
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            moveFromMotion.value(-1)
                            true
                        } else true
                    }
                    x > 0.45f -> {
                        if (stickStateX != 1) {
                            stickStateX = 1
                            lastStickTime = now
                            moveFromMotion.value(1)
                            true
                        } else if (now - lastStickTime > 320L) {
                            lastStickTime = now - 160L
                            moveFromMotion.value(1)
                            true
                        } else true
                    }
                    abs(x) < 0.25f -> {
                        stickStateX = 0
                        false
                    }
                    else -> false
                }
            }
            return@OnGenericMotionListener when {
                y < -0.45f -> {
                    if (stickStateY != -1) {
                        stickStateY = -1
                        lastStickTime = now
                        moveFromMotion.value(-1)
                        true
                    } else if (now - lastStickTime > 320L) {
                        lastStickTime = now - 160L
                        moveFromMotion.value(-1)
                        true
                    } else true
                }
                y > 0.45f -> {
                    if (stickStateY != 1) {
                        stickStateY = 1
                        lastStickTime = now
                        moveFromMotion.value(1)
                        true
                    } else if (now - lastStickTime > 320L) {
                        lastStickTime = now - 160L
                        moveFromMotion.value(1)
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
        currentView.setOnGenericMotionListener(motionListener)
        onDispose { currentView.setOnGenericMotionListener(null) }
    }

    val rootOnPreviewKey = rememberUpdatedState<(androidx.compose.ui.input.key.KeyEvent) -> Boolean> { event ->
        handleKeyEvent(event)
    }

    LaunchedEffect(targets, showGate) {
        runCatching { rootRequester.requestFocus() }
        val current = focusedTarget
        val firstRow = targets.firstOrNull { it is ConfigTarget.Row }
        // Land on the first setting row whenever rows resolve; keep row focus across
        // reloads, only restore a chrome target (back/menu/retry) when no rows exist.
        val shouldMove = current == null || current !in targets ||
            (current !is ConfigTarget.Row && firstRow != null)
        if (shouldMove) {
            val initial = firstRow ?: targets.firstOrNull()
            if (initial != null) focusTarget(initial)
        }
    }

    // Controller presence drives the hint strip only; input handling is always active.
    val devicesRepo = remember(context) { ControllerDeviceRepository(context) }
    val connectedDevices by devicesRepo.devices.collectAsStateWithLifecycle()
    val gamepadConnected = connectedDevices.any { it.type == InputDeviceType.GAMEPAD }
    DisposableEffect(devicesRepo) {
        devicesRepo.start()
        onDispose { devicesRepo.stop() }
    }

    val pageBackground = when {
        isInGame -> RPCSXColors.background.copy(alpha = 0.93f)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> RPCSXColors.background.copy(alpha = 0.55f)
        else -> RPCSXColors.background.copy(alpha = 0.96f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackground)
            .focusRequester(rootRequester)
            .focusable()
            .onPreviewKeyEvent { rootOnPreviewKey.value(it) }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConfigHeader(
                gamePath = gamePath,
                showMenu = menuAvailable,
                onClose = onClose,
                resetAllMenuOpen = resetAllMenuOpen,
                onResetAllMenuOpenChange = { resetAllMenuOpen = it },
                onResetAll = { showResetAllConfirm = true },
                onRemove = onRemove,
                backRequester = requesterFor(ConfigTarget.Back),
                menuRequester = requesterFor(ConfigTarget.Menu),
                onBackFocused = { focusedTarget = ConfigTarget.Back },
                onMenuFocused = { focusedTarget = ConfigTarget.Menu },
            )
            HorizontalDivider(color = GlassDivider, thickness = 1.dp)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showGate -> ConfigGatePanel(
                        onRetry = { gateRetryToken++ },
                        retryModifier = Modifier
                            .focusRequester(requesterFor(ConfigTarget.Retry))
                            .onFocusChanged { if (it.isFocused) focusedTarget = ConfigTarget.Retry }
                            .gamepadActivate { gateRetryToken++ }
                    )

                    tree == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = RPCSXColors.primary)
                    }

                    titleId == null -> ConfigMessagePanel(R.string.configure_game_unresolved_id)

                    else -> CuratedList(
                        structure = curated,
                        overrides = overrides,
                        resolvedGlobals = emptyMap(),
                        listState = listState,
                        rowModifier = { ref ->
                            Modifier
                                .focusRequester(requesterFor(ConfigTarget.Row(ref.index)))
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        focusedTarget = ConfigTarget.Row(ref.index)
                                        focusedSection = ref.sectionIndex
                                    }
                                }
                        },
                        onCommit = ::commitOverride,
                        onResetRow = ::resetRow
                    )
                }
            }

            if (gamepadConnected) {
                ConfigHintBar()
            }
        }
    }

    if (showResetAllConfirm && titleId != null) {
        AlertDialog(
            onDismissRequest = { showResetAllConfirm = false },
            title = { Text(stringResource(R.string.reset_all_game)) },
            text = { Text(stringResource(R.string.configure_game_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetAllConfirm = false
                    val tid = titleId
                    if (GameSettingsOverrides.clearGame(context, tid)) refreshOverrides()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ConfigHeader(
    gamePath: String?,
    showMenu: Boolean,
    onClose: (() -> Unit)?,
    resetAllMenuOpen: Boolean,
    onResetAllMenuOpenChange: (Boolean) -> Unit,
    onResetAll: () -> Unit,
    onRemove: (() -> Unit)?,
    backRequester: FocusRequester,
    menuRequester: FocusRequester,
    onBackFocused: () -> Unit,
    onMenuFocused: () -> Unit,
) {
    var backFocused by remember { mutableStateOf(false) }
    var menuFocused by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassHeader)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (onClose != null) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .focusRequester(backRequester)
                    .onFocusChanged {
                        backFocused = it.isFocused
                        if (it.isFocused) onBackFocused()
                    }
                    .border(
                        width = if (backFocused) 1.dp else 0.dp,
                        color = if (backFocused) RPCSXColors.focusRing else Color.Transparent,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                    contentDescription = stringResource(android.R.string.cancel),
                    tint = RPCSXColors.primary
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.configure_game).uppercase(),
                color = RPCSXColors.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 2.sp,
                maxLines = 1
            )
            val subtitle = gamePath
                ?.trimEnd('/')
                ?.substringAfterLast('/')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (subtitle != null) {
                Text(
                    text = subtitle.uppercase(),
                    color = RPCSXColors.textSecondary,
                    style = AppTypography.labelSmall,
                    maxLines = 1
                )
            }
        }
        if (showMenu) {
            Box {
                IconButton(
                    onClick = { onResetAllMenuOpenChange(true) },
                    modifier = Modifier
                        .focusRequester(menuRequester)
                        .onFocusChanged {
                            menuFocused = it.isFocused
                            if (it.isFocused) onMenuFocused()
                        }
                        .border(
                            width = if (menuFocused) 1.dp else 0.dp,
                            color = if (menuFocused) RPCSXColors.focusRing else Color.Transparent,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu),
                        contentDescription = "Game options menu",
                        tint = RPCSXColors.primary
                    )
                }
                DropdownMenu(
                    expanded = resetAllMenuOpen,
                    onDismissRequest = { onResetAllMenuOpenChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reset_all_game)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_restore),
                                contentDescription = null
                            )
                        },
                        onClick = {
                            onResetAllMenuOpenChange(false)
                            onResetAll()
                        }
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.remove_game)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                onResetAllMenuOpenChange(false)
                                onRemove()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigGatePanel(
    onRetry: () -> Unit,
    retryModifier: Modifier = Modifier,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassPanel)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.tune),
                contentDescription = null,
                tint = RPCSXColors.primary,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = stringResource(R.string.configure_game).uppercase(),
                color = RPCSXColors.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.configure_game_gate_description),
                color = RPCSXColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = onRetry,
                modifier = retryModifier
            ) {
                Text(stringResource(R.string.retry).uppercase())
            }
        }
    }
}

@Composable
private fun ConfigMessagePanel(messageRes: Int) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(messageRes),
            color = RPCSXColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassPanel)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .padding(24.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigHintBar() {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEE090C16))
            .drawBehind {
                drawLine(
                    color = GlassDivider,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        HintTextBadge(glyph = "D-PAD", label = "Navigate")
        HintGlyph(drawableRes = R.drawable.cross, label = "Select")
        HintGlyph(drawableRes = R.drawable.circle, label = "Back")
        HintGlyph(drawableRes = R.drawable.square, label = "Reset")
        HintGlyph(drawableRes = R.drawable.triangle, label = "Global")
        HintGlyph(drawableRes = R.drawable.l1, label = "Prev Section")
        HintGlyph(drawableRes = R.drawable.r1, label = "Next Section")
        HintGlyph(drawableRes = R.drawable.l2, label = "Scroll Up")
        HintGlyph(drawableRes = R.drawable.r2, label = "Scroll Down")
        HintGlyph(drawableRes = R.drawable.start, label = "Menu")
    }
}

@Composable
private fun HintGlyph(drawableRes: Int, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        HintLabel(label)
    }
}

@Composable
private fun HintTextBadge(glyph: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        ControllerGlyphBadge(glyph)
        HintLabel(label)
    }
}

@Composable
private fun HintLabel(label: String) {
    Text(
        text = label.uppercase(),
        color = RPCSXColors.textSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun CuratedList(
    structure: ConfigStructure,
    overrides: Map<String, String>,
    resolvedGlobals: Map<String, String>,
    listState: LazyListState,
    rowModifier: (ConfigRowRef) -> Modifier,
    onCommit: (String, JSONObject, String) -> Unit,
    onResetRow: (String, JSONObject) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 6.dp)
    ) {
        structure.sections.forEach { (section, refs) ->
            item(key = "header_${section.titleRes}") {
                SectionHeader(section)
            }
            refs.forEach { ref ->
                item(key = ref.path) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                    ) {
                        CuratedRow(
                            ref = ref,
                            sectionIcon = section.iconRes,
                            overridden = overrides.containsKey(ref.path),
                            effectiveEncoded = overrides[ref.path]
                                ?: resolvedGlobals[ref.path]
                                ?: engineEncodedValue(ref.node),
                            modifier = rowModifier(ref),
                            onCommit = onCommit,
                            onResetRow = onResetRow
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: CuratedSection) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x591C2850))
            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(
            painter = painterResource(id = section.iconRes),
            contentDescription = null,
            tint = RPCSXColors.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(section.titleRes).uppercase(),
            color = RPCSXColors.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.5.sp
        )
    }
}

/** Effective layering mirrors the engine tree: game override > global tier > engine value. */
@Composable
private fun CuratedRow(
    ref: ConfigRowRef,
    sectionIcon: Int,
    overridden: Boolean,
    effectiveEncoded: String,
    modifier: Modifier,
    onCommit: (String, JSONObject, String) -> Unit,
    onResetRow: (String, JSONObject) -> Unit
) {
    val path = ref.path
    val node = ref.node
    val label = path.substringAfterLast("@@")
    val effectiveDisplay = SettingsValueCodec.decodeToDisplay(effectiveEncoded)

    when (node.optString("type")) {
        "bool" -> SwitchPreference(
            modifier = modifier,
            checked = effectiveDisplay == "true",
            title = { PreferenceTitle(title = label) },
            subtitle = { OverrideStateBadge(overridden) },
            leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
            onClick = { value -> onCommit(path, node, value.toString()) },
            onLongClick = { if (overridden) onResetRow(path, node) }
        )

        "enum" -> {
            val allVariants = variantsOf(node)
            val variants = if (path == FRAME_LIMIT_PATH) {
                frameLimitOptions(allVariants, effectiveDisplay)
            } else {
                allVariants
            }
            val coerced =
                if (effectiveDisplay in variants) effectiveDisplay else variants.firstOrNull()
            if (!variants.isNullOrEmpty() && coerced != null) {
                SingleSelectionDialog(
                    currentValue = coerced,
                    values = variants,
                    modifier = modifier,
                    icon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
                    title = { PreferenceTitle(title = label) },
                    subtitle = { OverrideStateBadge(overridden) },
                    trailingContent = {
                        if (overridden) {
                            IconButton(onClick = { onResetRow(path, node) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_restore),
                                    contentDescription = stringResource(R.string.reset_row),
                                    tint = RPCSXColors.primary
                                )
                            }
                        }
                    },
                    onValueChange = { value -> onCommit(path, node, value) },
                    onLongClick = { if (overridden) onResetRow(path, node) },
                    valueToText = { value ->
                        if (path == FRAME_LIMIT_PATH) frameLimitUiText(value).title else value
                    },
                    item = { value, currentValue, onClick ->
                        if (path == FRAME_LIMIT_PATH) {
                            FrameLimitOptionRow(value, value == currentValue, onClick)
                        } else {
                            com.zenithblue.sambas3.ui.settings.components.preference.ListPreferenceItem<String> { it }(
                                value,
                                currentValue,
                                onClick
                            )
                        }
                    }
                )
            } else {
                UnrenderableNodeRow(
                    label,
                    sectionIcon,
                    effectiveDisplay,
                    overridden,
                    modifier = modifier,
                    onResetRow = { onResetRow(path, node) }
                )
            }
        }

        else -> {
            val minF = node.optString("min").toFloatOrNull()
            val maxF = node.optString("max").toFloatOrNull()
            val currentF = effectiveDisplay.toFloatOrNull()
            if (minF != null && maxF != null && minF < maxF && currentF != null) {
                SliderPreference(
                    modifier = modifier,
                    value = currentF.coerceIn(minF, maxF),
                    onValueChange = { value ->
                        onCommit(
                            path, node,
                            if (node.optString("type") == "float") value.toDouble().toString()
                            else value.toLong().toString()
                        )
                    },
                    title = label,
                    leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
                    valueRange = minF..maxF,
                    steps = (maxF - minF).toInt() - 1,
                    valueContent = {
                        Column {
                            Text(
                                text = effectiveDisplay,
                                color = if (overridden) RPCSXColors.primary
                                else RPCSXColors.textSecondary
                            )
                            OverrideStateBadge(overridden)
                        }
                    },
                    onLongClick = { if (overridden) onResetRow(path, node) }
                )
            } else {
                UnrenderableNodeRow(
                    label,
                    sectionIcon,
                    effectiveDisplay,
                    overridden,
                    modifier = modifier,
                    onResetRow = { onResetRow(path, node) }
                )
            }
        }
    }
}

/**
 * Read-only row for nodes whose shape makes them unrenderable as editors
 * (still shows the effective value plus the reset action when overridden).
 */
@Composable
private fun UnrenderableNodeRow(
    label: String,
    sectionIcon: Int,
    display: String,
    overridden: Boolean,
    modifier: Modifier = Modifier,
    onResetRow: () -> Unit
) {
    RegularPreference(
        modifier = modifier,
        title = { PreferenceTitle(title = label) },
        leadingIcon = { PreferenceIcon(icon = painterResource(id = sectionIcon)) },
        subtitle = { OverrideStateBadge(overridden) },
        value = {
            Text(
                text = display,
                color = if (overridden) RPCSXColors.primary else RPCSXColors.textSecondary
            )
        },
        onClick = {},
        onLongClick = if (overridden) onResetRow else ({})
    )
}

@Composable
private fun OverrideStateBadge(overridden: Boolean) {
    Text(
        text = stringResource(if (overridden) R.string.override_value else R.string.use_global)
            .uppercase(),
        color = if (overridden) RPCSXColors.primary else RPCSXColors.textSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
}

private fun findCuratedNode(root: JSONObject?, path: String): JSONObject? {
    var current = root ?: return null
    for (part in path.split("@@")) {
        current = current.optJSONObject(part) ?: return null
    }
    return if (current.has("type")) current else null
}

private fun nodeSpec(node: JSONObject): SettingNodeSpec = SettingNodeSpec(
    type = node.optString("type"),
    min = if (node.has("min")) node.optString("min") else null,
    max = if (node.has("max")) node.optString("max") else null,
    default = if (node.has("default")) node.optString("default") else null
)

private fun rawDisplayValue(node: JSONObject): String = when (node.optString("type")) {
    "bool" -> node.optBoolean("value", false).toString()
    "enum", "string" -> node.optString("value")
    else -> node.optString("value")
}

private fun engineEncodedValue(node: JSONObject): String =
    SettingsValueCodec.encodedFromNode(nodeSpec(node), rawDisplayValue(node))

private fun variantsOf(node: JSONObject): List<String> = try {
    val array: JSONArray = node.getJSONArray("variants")
    List(array.length()) { index -> array.getString(index) }
} catch (e: Exception) {
    emptyList()
}

/**
 * Fullscreen in-game wrapper for [GameConfigureScreen]; hosts dialogs with
 * respectHostSuppression = false so engine rejections render during gameplay.
 */
@Composable
fun GameConfigureOverlay(
    gamePath: String?,
    onBackToMenu: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        GameConfigureScreen(
            gamePath = gamePath,
            isInGame = true,
            onClose = onBackToMenu
        )
        AlertDialogQueue.AlertDialog(respectHostSuppression = false)
    }
}
