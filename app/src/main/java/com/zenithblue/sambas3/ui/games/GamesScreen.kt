package com.zenithblue.sambas3.ui.games

import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.BatteryManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Log
import coil3.compose.AsyncImage
import com.zenithblue.sambas3.*
import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.ui.games.preview.GamePreviewModel
import com.zenithblue.sambas3.ui.games.preview.GamePreviewRepository
import com.zenithblue.sambas3.ui.games.launch.GameLaunchCenter
import com.zenithblue.sambas3.ui.games.launch.GameLaunchRepository
import com.zenithblue.sambas3.ui.games.launch.GameSavestateRepository
import com.zenithblue.sambas3.iso.DirectIsoSession
import com.zenithblue.sambas3.ui.ingame.TrophiesData
import com.zenithblue.sambas3.ui.achievements.AchievementEvents
import com.zenithblue.sambas3.ui.achievements.AchievementRepository
import com.zenithblue.sambas3.crash.HomeRecoveryRepository
import com.zenithblue.sambas3.crash.HomeRecoveryState
import com.zenithblue.sambas3.crash.RecoveryAction
import com.zenithblue.sambas3.ui.crash.CrashDetailsSheet
import com.zenithblue.sambas3.ui.crash.CrashRecoveryCard
import com.zenithblue.sambas3.ui.crash.StopFailureCard
import com.zenithblue.sambas3.ui.components.DialogBackgroundBlur
import com.zenithblue.sambas3.ui.components.DialogImmersiveSystemBars
import com.zenithblue.sambas3.session.EmulatorStopCoordinator
import com.zenithblue.sambas3.iso.DirectIsoManager
import com.zenithblue.sambas3.utils.FileUtil
import com.zenithblue.sambas3.utils.ScannedFoldersRepository
import kotlin.math.abs
import kotlin.concurrent.thread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class PagerItem {
    abstract val stableKey: String
    data class GameItem(val game: Game) : PagerItem() {
        override val stableKey: String get() = "game:${com.zenithblue.sambas3.GameIdentity.key(game.info.path, game.info.name.value)}"
    }
    data class AddGame(val disabled: Boolean = false, val position: String = "end") : PagerItem() {
        override val stableKey: String get() = if (disabled) "add:disabled:$position" else "add:$position"
    }
    data object FirmwareCard : PagerItem() {
        override val stableKey: String get() = "firmware"
    }
    // Phases 3-4 will add SourceCandidate/PendingImport; keep keys stable when merging later.
    data class SourceCandidate(
        val titleId: String?,
        val displayName: String,
        val sourceUri: String,
        val sourceKind: com.zenithblue.sambas3.utils.GameSourceKind? = null
    ) : PagerItem() {
        override val stableKey: String get() = "source:${titleId ?: displayName.lowercase()}"
    }
    data class PendingImport(val progressId: Long, val provisionalTitleId: String?, val displayName: String?) : PagerItem() {
        override val stableKey: String get() = "import:$progressId"
    }
}

/** Pure function for library pager derivation — unit-testable. */
fun buildLibraryPagerItems(
    visibleGames: List<Game>,
    sourceCandidates: List<PagerItem.SourceCandidate> = emptyList(),
    pendingImports: List<PagerItem.PendingImport> = emptyList(),
    hasFw: Boolean,
    isFwInstalling: Boolean,
    showBothEnds: Boolean = false
): List<PagerItem> = buildList {
    val hasLibrary = visibleGames.isNotEmpty() || pendingImports.isNotEmpty()
    if (!hasLibrary) {
        if (!hasFw) add(PagerItem.FirmwareCard)
    } else {
        addAll(visibleGames.map { PagerItem.GameItem(it) })
        addAll(pendingImports)
    }
}

private fun isSystemFirmwareEntry(game: Game): Boolean {
    val path = game.info.path.lowercase()
    return path.endsWith("/vsh.self") || path.contains("/dev_flash/")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    installPkgLauncher: ActivityResultLauncher<String>? = null,
    gameFolderPickerLauncher: ActivityResultLauncher<Uri?>? = null,
    installFwLauncher: ActivityResultLauncher<String>? = null,
    navigateToSettings: (() -> Unit)? = null,
    navigateToDrivers: (() -> Unit)? = null,
    navigateToPatches: (() -> Unit)? = null,
    navigateToLogs: ((String?) -> Unit)? = null,
    navigateToCrashLogs: ((String?) -> Unit)? = null,
    emulatorState: State<EmulatorState> = mutableStateOf(EmulatorState.Stopped),
    emulatorActiveGame: State<String?> = mutableStateOf(null)
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator
            .reconcileInterruptedState(
                context
            )
    }
    val games = remember { GameRepository.list() }
    val rpcsxLibrary by remember { RPCSX.activeLibrary }
    val recoveryState by HomeRecoveryRepository.state.collectAsState()
    val stopState by EmulatorStopCoordinator.state.collectAsState()
    var detailsState by remember { mutableStateOf<HomeRecoveryState?>(null) }
    LaunchedEffect(Unit) {
        HomeRecoveryRepository.refresh(context)
        ScannedFoldersRepository.load(context)
    }
    val scannedFolders by ScannedFoldersRepository.foldersFlow.collectAsState()

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
                Image(
                    painter = painterResource(R.mipmap.ic_sambas3_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )
                Text("SambaS3", style = AppTypography.displayLarge.copy(letterSpacing = 4.sp), color = RPCSXColors.primary)
                CircularProgressIndicator(color = RPCSXColors.primary, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.missing_rpcsx_lib), style = AppTypography.labelSmall, color = RPCSXColors.textSecondary)
            }
        }
        return
    }

    // Home content is now composed and loaded — release the boot splash.
    LaunchedEffect(Unit) {
        com.zenithblue.sambas3.ui.splash.SplashGate.homeReady()
    }

    var focusedIndex by remember { mutableStateOf(if (games.isNotEmpty()) 0 else -1) }
    var bootingGame by remember { mutableStateOf<Game?>(null) }
    var launchCenterGame by remember { mutableStateOf<Game?>(null) }
    var patchTitleId by remember { mutableStateOf<String?>(null) }
    var stoppedTrophies by remember { mutableStateOf<TrophiesData?>(null) }
    var stoppedTrophiesLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        AchievementEvents.invalidations.collect {
            if (stoppedTrophies != null && launchCenterGame != null) {
                stoppedTrophies = null
                stoppedTrophiesLoading = true
            }
        }
    }
    LaunchedEffect(stoppedTrophiesLoading, launchCenterGame?.info?.path) {
        val game = launchCenterGame
        if (!stoppedTrophiesLoading || game == null) return@LaunchedEffect
        val titleId = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
        val hdd = titleId?.let { AchievementRepository.title(it, force = true) }
        stoppedTrophies = if (hdd != null && hdd.available) {
            hdd
        } else {
            val sourceUri = game.info.sourceUri.value
            val isoUri = sourceUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val isDirectIso = game.info.sourceMode.value == GameSourceMode.DIRECT_ISO ||
                sourceUri?.endsWith(".iso", ignoreCase = true) == true
            if (titleId != null && isDirectIso && isoUri != null) {
                runCatching {
                    DirectIsoSession.withReadOnly(context, isoUri) { fd ->
                        AchievementRepository.titleFromIsoNow(titleId, fd, force = true)
                    }
                }.onFailure { Log.w("S3TROPHY", "iso trophy fallback failed title=$titleId: ${it.message}") }
                    .getOrNull() ?: hdd
            } else hdd
        }
        stoppedTrophiesLoading = false
    }
    val recoveryScope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    var folderImportResult by remember { mutableStateOf<DirectIsoManager.IsoFolderImportResult?>(null) }
    var scanningFolder by remember { mutableStateOf(false) }
    var isFoldersExpanded by remember { mutableStateOf(false) }
    var configureGameTarget by remember { mutableStateOf<Game?>(null) }
    var removeGameTarget by remember { mutableStateOf<Game?>(null) }
    var removingGame by remember { mutableStateOf(false) }
    var removeGameFailed by remember { mutableStateOf(false) }
    var clearCacheTarget by remember { mutableStateOf<Game?>(null) }
    var clearingCache by remember { mutableStateOf(false) }
    var clearCacheFailed by remember { mutableStateOf(false) }
    // Gameplay ownership — STOP only when actual game is running/paused, not compile-only engine busy
    val gameplayRunning = emulatorActiveGame.value != null && (emulatorState.value == EmulatorState.Running || emulatorState.value == EmulatorState.Paused)
    val isRunning = gameplayRunning // legacy alias, but STOP must use gameplayRunning
    val stopInProgress = stopState is com.zenithblue.sambas3.session.EmulatorStopState.Stopping

    LaunchedEffect(stopInProgress) {
        if (stopInProgress) launchCenterGame = null
    }

    val recoverySession = when (val value = recoveryState) {
        is HomeRecoveryState.ConfirmedCrash -> value.session
        is HomeRecoveryState.Interrupted -> value.session
        is HomeRecoveryState.ActionFailed -> value.session
        else -> null
    }
    val recoveryGamePath = when (val value = recoveryState) {
        is HomeRecoveryState.LoadFailure -> value.gamePath
        else -> recoverySession?.gamePath
    }
    val recoveryGame = games.firstOrNull { it.info.path == recoveryGamePath }

    fun launchRecovery(game: Game?, savePath: String?, slot: Int?, action: RecoveryAction) {
        if (game == null) {
            HomeRecoveryRepository.markActionFailed(context, recoverySession, "Game is no longer in the library")
            return
        }
        HomeRecoveryRepository.markActionRunning(action)
        recoveryScope.launch(Dispatchers.IO) {
            val stop = com.zenithblue.sambas3.session.CoreRecoveryCoordinator.ensureStoppedForFreshBoot(
                reason = "Home recovery $action",
                state = { RPCSX.getState() },
                kill = { RPCSX.instance.kill() },
                onLog = { Log.i("S3RECOVERY", it) },
            )
            if (stop != com.zenithblue.sambas3.session.StopResult.AlreadyStopped &&
                stop != com.zenithblue.sambas3.session.StopResult.Stopped
            ) {
                HomeRecoveryRepository.markActionFailed(context, recoverySession, "Core did not stop ($stop)")
                return@launch
            }
            if (savePath != null) PendingSavestateRecoveryStore.clear(context)
            withContext(Dispatchers.Main) { bootGame(context, game, savePath, slot) }
        }
    }

    fun safeRetryRecovery(state: HomeRecoveryState) {
        val game = recoveryGame
        val loadFailure = state as? HomeRecoveryState.LoadFailure
        val selectedSave = loadFailure?.savestatePath?.takeIf { it.isNotBlank() }
            ?: game?.let {
                GameSavestateRepository.slots(context, it)
                    .filter { save -> save.exists && save.path != null }
                    .maxByOrNull { save -> save.mtimeMs }
                    ?.let { save -> save.path to save.slot }
            }?.let { it.first }
        val selectedSlot = loadFailure?.slot ?: game?.let {
            GameSavestateRepository.slots(context, it)
                .filter { save -> save.exists && save.path == selectedSave }
                .firstOrNull()?.slot
        }
        detailsState = null
        launchRecovery(game, selectedSave, selectedSlot, RecoveryAction.SafeRetry)
    }

    fun recoverySessionId(state: HomeRecoveryState): String? = when (state) {
        is HomeRecoveryState.ConfirmedCrash -> state.session.sessionId
        is HomeRecoveryState.Interrupted -> state.session.sessionId
        is HomeRecoveryState.ActionFailed -> state.session?.sessionId
        is HomeRecoveryState.LoadFailure -> state.sessionId ?: state.report?.sessionId
        else -> null
    }

    fun exportRecoveryReport(state: HomeRecoveryState) {
        val sessionId = recoverySessionId(state)
        if (sessionId.isNullOrBlank()) {
            android.widget.Toast.makeText(context, "No session report is available", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        detailsState = null
        recoveryScope.launch(Dispatchers.IO) {
            val ok = com.zenithblue.sambas3.logging.SessionExport.shareSession(context, sessionId)
            if (!ok) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Report could not be collected", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val bootScale by animateFloatAsState(if (bootingGame != null) 5f else 1f, animationSpec = tween(700))
    val bootAlpha by animateFloatAsState(if (bootingGame != null) 0f else 1f, animationSpec = tween(500))

    LaunchedEffect(bootingGame) {
        if (bootingGame != null) {
            kotlinx.coroutines.delay(600)
            bootGame(context, bootingGame!!)
            bootingGame = null
        }
    }

    val directIsoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            var persisted = false
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                persisted = context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            } catch (e: Exception) {
                android.util.Log.e("S3ISO", "unavailable reason=persist-failed ${e.message}")
            }
            android.util.Log.i("S3ISO", "source_selected uri=$uri")
            android.util.Log.i("S3ISO", "permission_persisted=$persisted uri=$uri")
            if (uri.scheme == "content" && !persisted) {
                context.mainExecutor.execute {
                    android.widget.Toast.makeText(
                        context,
                        "This provider did not grant persistent read access. Select the ISO again from Documents.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return@rememberLauncherForActivityResult
            }
            kotlin.concurrent.thread(name = "sambas3-direct-iso-register") {
                val result = runCatching {
                    DirectIsoManager.registerDirectIso(context, uri)
                }
                context.mainExecutor.execute {
                    result.onSuccess { registered ->
                        val name = registered.game.info.name.value ?: "Game"
                        val message = when (registered) {
                            is DirectIsoManager.DirectIsoRegisterResult.AlreadyImported ->
                                context.getString(R.string.game_already_imported, name)
                            is DirectIsoManager.DirectIsoRegisterResult.Registered ->
                                "Direct ISO registered: $name"
                        }
                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                    }.onFailure { err ->
                        android.widget.Toast.makeText(
                            context,
                            "Failed to register ISO: ${err.message ?: "Unknown error"}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some providers return a readable tree without persistable access.
            }
            folderImportResult = null
            scanningFolder = true
            thread(name = "sambas3-folder-scan") {
                val result = ScannedFoldersRepository.addAndImport(context, uri)
                context.mainExecutor.execute {
                    folderImportResult = result
                    scanningFolder = false
                }
            }
        }
    }

    val triggerRefresh = {
        if (!scanningFolder) {
            if (scannedFolders.isEmpty()) {
                folderPickerLauncher.launch(null)
            } else {
                scanningFolder = true
                thread(name = "sambas3-folder-refresh") {
                    val result = ScannedFoldersRepository.refreshAll(context)
                    context.mainExecutor.execute {
                        folderImportResult = result
                        scanningFolder = false
                    }
                }
            }
        }
    }

    val fwVersion by remember { FirmwareRepository.version }
    val fwProgressId by remember { FirmwareRepository.progressChannel }
    val isFwInstalling = fwProgressId != null
    val hasFw = fwVersion != null
    val installPpu by CompileProgressBridge.installState.collectAsState()
    val prelaunchPpu by CompileProgressBridge.prelaunchState.collectAsState()
    val runtimePpu by CompileProgressBridge.state.collectAsState()
    val ppuReadinessRevision by PpuReadinessStore.revision.collectAsState()
    val ppuCoordinatorRevision by com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.coordinatorRevision.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val ppuUiRevision = ppuReadinessRevision + ppuCoordinatorRevision
    val activeInstallId by GameRepository.activeInstallProgress
    val activeInstallEntry = ProgressRepository.getItem(activeInstallId)?.value
    val isPackageInstalling = activeInstallId != null

    // BLOCKER D: observe pending import sessions — one stable card per import
    val importSessions by com.zenithblue.sambas3.ImportSessionStore.sessions.collectAsState()

    // Blocker B fix: do not memoize mutable SnapshotStateList with remember(games) or remember(size).
    // Derive directly during composition so placeholder add/remove/replace is observed.
    // Hide legacy "$" placeholder entirely — pending UI is now ImportSession/PendingImport, not a fake Game.
    // Native firmware collection exposes vsh.self as a "game". It is not
    // playable and its card incorrectly showed VSH as a game.
    val visibleGames: List<Game> = games.filterNot {
        it.info.path == "$" || isSystemFirmwareEntry(it)
    }

    var isGridView by rememberSaveable {
        mutableStateOf(com.zenithblue.sambas3.utils.GeneralSettings["home_view_mode"] as? String == "grid")
    }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var focusedGridIndex by rememberSaveable { mutableIntStateOf(0) }

    val filteredGames: List<Game> = remember(visibleGames, searchQuery) {
        if (searchQuery.isBlank()) visibleGames
        else {
            val q = searchQuery.trim().lowercase()
            visibleGames.filter { game ->
                val name = (game.info.name.value ?: "").lowercase()
                val path = game.info.path.lowercase()
                name.contains(q) || path.contains(q)
            }
        }
    }

    val installedTitleIds = visibleGames.mapNotNull { com.zenithblue.sambas3.GameIdentity.titleIdOrNull(it.info.path, it.info.name.value) }.map { it.uppercase() }.toSet()
    // Pending imports — hide if same title already installed (installed wins, PPU shows on Game card via installPpu)
    val pendingItems: List<PagerItem.PendingImport> = importSessions.mapNotNull { sess ->
        val prov = sess.provisionalTitleId?.uppercase()
        val resolved = sess.resolvedTitleId?.uppercase()
        if ((prov != null && prov in installedTitleIds) || (resolved != null && resolved in installedTitleIds)) {
            // Already installed — let Game card show PPU, not duplicate pending
            return@mapNotNull null
        }
        PagerItem.PendingImport(sess.progressId, prov ?: resolved, sess.sourceName)
    }
    val filteredPendingItems: List<PagerItem.PendingImport> = remember(pendingItems, searchQuery) {
        if (searchQuery.isBlank()) pendingItems
        else {
            val q = searchQuery.trim().lowercase()
            pendingItems.filter { sess ->
                (sess.displayName?.lowercase()?.contains(q) == true) || (sess.provisionalTitleId?.lowercase()?.contains(q) == true)
            }
        }
    }
    val showBothEnds = (filteredGames.size + filteredPendingItems.size) > 5
    val pagerItems: List<PagerItem> = buildLibraryPagerItems(filteredGames, emptyList(), filteredPendingItems, hasFw, isFwInstalling, showBothEnds)
    val initialPage = 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pagerItems.size })
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 800 && configuration.screenHeightDp >= 580
    // Clamp pager when list shrinks (removal crash safety — F1)
    LaunchedEffect(pagerItems.size) {
        if (pagerItems.isNotEmpty()) {
            val target = pagerState.currentPage.coerceAtMost(pagerItems.lastIndex)
            if (target != pagerState.currentPage) {
                try { pagerState.scrollToPage(target) } catch (_: Exception) {}
            }
        }
    }
    LaunchedEffect(filteredGames.size) {
        if (filteredGames.isNotEmpty()) {
            focusedGridIndex = focusedGridIndex.coerceIn(0, filteredGames.lastIndex)
        } else {
            focusedGridIndex = 0
        }
    }
    val currentItem = pagerItems.getOrNull(pagerState.currentPage)
    val selectedGame = if (isGridView) {
        filteredGames.getOrNull(focusedGridIndex) ?: filteredGames.firstOrNull()
    } else {
        (currentItem as? PagerItem.GameItem)?.game
    }
    val selectedIconPath = selectedGame?.info?.iconPath?.value
    val selectedBgPreview by androidx.compose.runtime.produceState<Any?>(
        initialValue = null,
        key1 = selectedGame?.info?.path,
        key2 = selectedIconPath
    ) {
        value = if (selectedGame != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                when (val bg = GamePreviewRepository.resolveBackground(context, selectedGame)) {
                    is GamePreviewModel.LocalFile -> bg.file
                    is GamePreviewModel.ContentUri -> bg.uri
                    is GamePreviewModel.None -> null
                }
            }
        } else null
    }
    val fullscreenAmbientModel: Any = selectedBgPreview ?: R.drawable.default_wallpaper

    val homeScope = rememberCoroutineScope()

    fun navigateLibraryLeft() {
        if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) return
        val current = pagerState.currentPage
        if (current > 0) {
            homeScope.launch {
                try { pagerState.animateScrollToPage(current - 1) } catch (_: Exception) {}
            }
        }
    }

    fun navigateLibraryRight() {
        if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) return
        val current = pagerState.currentPage
        if (current < pagerItems.lastIndex) {
            homeScope.launch {
                try { pagerState.animateScrollToPage(current + 1) } catch (_: Exception) {}
            }
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { rootFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    val gridColumns = if (isTablet) 5 else 4

    fun navigateGrid(deltaX: Int, deltaY: Int) {
        if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) return
        if (filteredGames.isEmpty()) return

        val current = focusedGridIndex
        val total = filteredGames.size
        val col = current % gridColumns
        val row = current / gridColumns
        val totalRows = (total + gridColumns - 1) / gridColumns

        var target = current

        if (deltaX < 0) {
            if (col > 0) target = current - 1
        } else if (deltaX > 0) {
            if (col < gridColumns - 1 && current + 1 < total) target = current + 1
        }

        if (deltaY < 0) {
            if (row > 0) target = (row - 1) * gridColumns + col
        } else if (deltaY > 0) {
            if (row < totalRows - 1) {
                val candidate = (row + 1) * gridColumns + col
                target = if (candidate < total) candidate else total - 1
            }
        }

        if (target != current) {
            focusedGridIndex = target
        }
    }

    BackHandler(enabled = isFoldersExpanded) {
        isFoldersExpanded = false
    }

    BackHandler(enabled = isSearchExpanded) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            isSearchExpanded = false
        }
    }

    var stickArmedX by remember { mutableStateOf(true) }
    var stickArmedY by remember { mutableStateOf(true) }
    var triggerArmedL2 by remember { mutableStateOf(true) }
    var triggerArmedR2 by remember { mutableStateOf(true) }
    var stickHoldStartTimeX by remember { mutableLongStateOf(0L) }
    var stickHoldStartTimeY by remember { mutableLongStateOf(0L) }
    var lastStickStepTimeX by remember { mutableLongStateOf(0L) }
    var lastStickStepTimeY by remember { mutableLongStateOf(0L) }
    var lastKeyRepeatTime by remember { mutableLongStateOf(0L) }

    val currentView = LocalView.current
    DisposableEffect(currentView) {
        val motionListener = View.OnGenericMotionListener { _, event ->
            if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) {
                return@OnGenericMotionListener false
            }
            val source = event.source
            val isGamepadOrJoystick = (source and InputDevice.SOURCE_GAMEPAD != 0) ||
                (source and InputDevice.SOURCE_JOYSTICK != 0)
            if (!isGamepadOrJoystick) return@OnGenericMotionListener false

            // Trigger Axes (L2 / R2 analog triggers on gamepads)
            val l2Val = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE),
                event.getAxisValue(MotionEvent.AXIS_THROTTLE).coerceAtLeast(0f),
            )
            val r2Val = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS),
            )

            if (l2Val > 0.50f) {
                if (triggerArmedL2) {
                    triggerArmedL2 = false
                    isFoldersExpanded = !isFoldersExpanded
                    return@OnGenericMotionListener true
                }
            } else if (l2Val < 0.20f) {
                triggerArmedL2 = true
            }

            if (r2Val > 0.50f) {
                if (triggerArmedR2) {
                    triggerArmedR2 = false
                    triggerRefresh()
                    return@OnGenericMotionListener true
                }
            } else if (r2Val < 0.20f) {
                triggerArmedR2 = true
            }

            // Left Stick ONLY (not Hat / D-pad):
            val rawX = event.getAxisValue(MotionEvent.AXIS_X)
            val rawY = event.getAxisValue(MotionEvent.AXIS_Y)
            val now = android.os.SystemClock.uptimeMillis()

            // Horizontal Axis
            if (rawX < -0.55f) {
                if (stickArmedX) {
                    stickArmedX = false
                    stickHoldStartTimeX = now
                    lastStickStepTimeX = now
                    if (isGridView) navigateGrid(deltaX = -1, deltaY = 0) else navigateLibraryLeft()
                } else if (now - stickHoldStartTimeX > 380L && now - lastStickStepTimeX > 200L) {
                    lastStickStepTimeX = now
                    if (isGridView) navigateGrid(deltaX = -1, deltaY = 0) else navigateLibraryLeft()
                }
            } else if (rawX > 0.55f) {
                if (stickArmedX) {
                    stickArmedX = false
                    stickHoldStartTimeX = now
                    lastStickStepTimeX = now
                    if (isGridView) navigateGrid(deltaX = 1, deltaY = 0) else navigateLibraryRight()
                } else if (now - stickHoldStartTimeX > 380L && now - lastStickStepTimeX > 200L) {
                    lastStickStepTimeX = now
                    if (isGridView) navigateGrid(deltaX = 1, deltaY = 0) else navigateLibraryRight()
                }
            } else if (abs(rawX) < 0.20f) {
                stickArmedX = true
            }

            // Vertical Axis (Grid only)
            if (isGridView) {
                if (rawY < -0.55f) {
                    if (stickArmedY) {
                        stickArmedY = false
                        stickHoldStartTimeY = now
                        lastStickStepTimeY = now
                        navigateGrid(deltaX = 0, deltaY = -1)
                    } else if (now - stickHoldStartTimeY > 380L && now - lastStickStepTimeY > 200L) {
                        lastStickStepTimeY = now
                        navigateGrid(deltaX = 0, deltaY = -1)
                    }
                } else if (rawY > 0.55f) {
                    if (stickArmedY) {
                        stickArmedY = false
                        stickHoldStartTimeY = now
                        lastStickStepTimeY = now
                        navigateGrid(deltaX = 0, deltaY = 1)
                    } else if (now - stickHoldStartTimeY > 380L && now - lastStickStepTimeY > 200L) {
                        lastStickStepTimeY = now
                        navigateGrid(deltaX = 0, deltaY = 1)
                    }
                } else if (abs(rawY) < 0.20f) {
                    stickArmedY = true
                }
            }

            false
        }

        val keyListener = View.OnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) {
                return@OnKeyListener false
            }
            if (event.repeatCount > 0) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastKeyRepeatTime < 200L) return@OnKeyListener true
                lastKeyRepeatTime = now
            }
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_START -> {
                    navigateToSettings?.invoke()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    showImportDialog = true
                    true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    isSearchExpanded = !isSearchExpanded
                    if (!isSearchExpanded) searchQuery = ""
                    true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    isGridView = !isGridView
                    com.zenithblue.sambas3.utils.GeneralSettings.setValue("home_view_mode", if (isGridView) "grid" else "carousel")
                    true
                }
                KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                    isFoldersExpanded = !isFoldersExpanded
                    true
                }
                KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                    triggerRefresh()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> {
                    if (isGridView) navigateGrid(deltaX = -1, deltaY = 0) else navigateLibraryLeft()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                    if (isGridView) navigateGrid(deltaX = 1, deltaY = 0) else navigateLibraryRight()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isGridView) { navigateGrid(deltaX = 0, deltaY = -1); true } else false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isGridView) { navigateGrid(deltaX = 0, deltaY = 1); true } else false
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (isGridView) {
                        val g = filteredGames.getOrNull(focusedGridIndex)
                        if (g != null && g.info.path != "$" && g.findProgress(GameProgressType.Install) == null) {
                            launchCenterGame = g
                            true
                        } else false
                    } else {
                        when (val item = currentItem) {
                            is PagerItem.GameItem -> {
                                val g = item.game
                                if (g.info.path != "$" && g.findProgress(GameProgressType.Install) == null) {
                                    launchCenterGame = g
                                    true
                                } else false
                            }
                            is PagerItem.FirmwareCard -> {
                                installFwLauncher?.launch("*/*")
                                true
                            }
                            else -> false
                        }
                    }
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    if (isFoldersExpanded) {
                        isFoldersExpanded = false
                        true
                    } else if (isSearchExpanded) {
                        if (searchQuery.isNotEmpty()) searchQuery = "" else isSearchExpanded = false
                        true
                    } else if (HomeRecoveryRepository.state.value !is HomeRecoveryState.None &&
                        HomeRecoveryRepository.state.value !is HomeRecoveryState.ActionRunning) {
                        HomeRecoveryRepository.dismiss(context)
                        true
                    } else false
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RPCSXColors.background)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = keyEvent.nativeKeyEvent.keyCode
                if (showImportDialog || scanningFolder || folderImportResult != null || detailsState != null || launchCenterGame != null) {
                    return@onPreviewKeyEvent false
                }
                if (keyEvent.nativeKeyEvent.repeatCount > 0) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastKeyRepeatTime < 200L) return@onPreviewKeyEvent true
                    lastKeyRepeatTime = now
                }
                when {
                    keyEvent.key == Key.ButtonStart || code == KeyEvent.KEYCODE_BUTTON_START -> {
                        navigateToSettings?.invoke()
                        true
                    }
                    keyEvent.key == Key.ButtonSelect || code == KeyEvent.KEYCODE_BUTTON_SELECT -> {
                        showImportDialog = true
                        true
                    }
                    // Triangle (Y): Search toggle
                    keyEvent.key == Key.ButtonY || code == KeyEvent.KEYCODE_BUTTON_Y -> {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) searchQuery = ""
                        true
                    }
                    // Square (X): Grid / Carousel toggle
                    keyEvent.key == Key.ButtonX || code == KeyEvent.KEYCODE_BUTTON_X -> {
                        isGridView = !isGridView
                        com.zenithblue.sambas3.utils.GeneralSettings.setValue("home_view_mode", if (isGridView) "grid" else "carousel")
                        true
                    }
                    keyEvent.key == Key.ButtonL2 || code == KeyEvent.KEYCODE_BUTTON_L2 ||
                    code == KeyEvent.KEYCODE_BUTTON_THUMBL -> {
                        isFoldersExpanded = !isFoldersExpanded
                        true
                    }
                    keyEvent.key == Key.ButtonR2 || code == KeyEvent.KEYCODE_BUTTON_R2 ||
                    code == KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                        triggerRefresh()
                        true
                    }
                    keyEvent.key == Key.DirectionLeft || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (isGridView) navigateGrid(deltaX = -1, deltaY = 0) else navigateLibraryLeft()
                        true
                    }
                    keyEvent.key == Key.DirectionRight || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (isGridView) navigateGrid(deltaX = 1, deltaY = 0) else navigateLibraryRight()
                        true
                    }
                    keyEvent.key == Key.DirectionUp || code == KeyEvent.KEYCODE_DPAD_UP -> {
                        if (isGridView) { navigateGrid(deltaX = 0, deltaY = -1); true } else false
                    }
                    keyEvent.key == Key.DirectionDown || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isGridView) { navigateGrid(deltaX = 0, deltaY = 1); true } else false
                    }
                    keyEvent.key == Key.ButtonA || keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter ||
                    code == KeyEvent.KEYCODE_BUTTON_A || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER -> {
                        if (isGridView) {
                            val g = filteredGames.getOrNull(focusedGridIndex)
                            if (g != null && g.info.path != "$" && g.findProgress(GameProgressType.Install) == null) {
                                launchCenterGame = g
                                true
                            } else false
                        } else {
                            when (val item = currentItem) {
                                is PagerItem.GameItem -> {
                                    val g = item.game
                                    if (g.info.path != "$" && g.findProgress(GameProgressType.Install) == null) {
                                        launchCenterGame = g
                                        true
                                    } else false
                                }
                                is PagerItem.FirmwareCard -> {
                                    installFwLauncher?.launch("*/*")
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                    keyEvent.key == Key.ButtonB || keyEvent.key == Key.Back ||
                    code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK -> {
                        if (isFoldersExpanded) {
                            isFoldersExpanded = false
                            true
                        } else if (isSearchExpanded) {
                            if (searchQuery.isNotEmpty()) searchQuery = "" else isSearchExpanded = false
                            true
                        } else if (recoveryState !is HomeRecoveryState.None &&
                            recoveryState !is HomeRecoveryState.ActionRunning) {
                            HomeRecoveryRepository.dismiss(context)
                            true
                        } else false
                    }
                    else -> false
                }
            }
    ) {
        // Crisp cover/artwork of focused game as home background (or default wallpaper fallback)
        AsyncImage(
            model = fullscreenAmbientModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.85f),
            onError = { err ->
                val title = (currentItem as? PagerItem.GameItem)?.game?.info?.name?.value
                val path = (currentItem as? PagerItem.GameItem)?.game?.info?.path
                Log.w("GamePreview", "preview error title=$title path=$path err=${err.result.throwable?.message}")
            }
        )

        // Subtle dark cinematic tint for readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.35f),
                        )
                    )
                )
        )

        // Vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color(0x99000000)),
                            center = Offset(size.width / 2, size.height / 2),
                            radius = size.width
                        ),
                        size = size
                    )
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Top Nav Bar: SambaS3 brand, active Game Title & Tag, Clock & Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(bootAlpha)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Logo + App Name + Active Game Title & Tag
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_sambas3_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "SambaS3",
                        style = AppTypography.titleMedium.copy(letterSpacing = 1.sp, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        color = RPCSXColors.primary
                    )

                    if (!isTablet && !isSearchExpanded) {
                        val displayedGame = if (isGridView) {
                            filteredGames.getOrNull(focusedGridIndex) ?: filteredGames.firstOrNull()
                        } else {
                            (currentItem as? PagerItem.GameItem)?.game
                        }
                        if (displayedGame != null) {
                            val activeGame = displayedGame
                            val isActiveGameRunning = isRunning && emulatorActiveGame.value == activeGame.info.path
                            val titleText = when {
                                activeGame.info.path == "$" -> "IMPORTING..."
                                else -> GameIdentity.displayName(
                                    activeGame.info.path,
                                    activeGame.info.name.value,
                                ).uppercase()
                            }
                            val tag = activeGame.info.path.substringAfterLast("/")

                            Spacer(Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(16.dp)
                                    .background(RPCSXColors.outlineVariant.copy(alpha = 0.6f))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = titleText,
                                style = AppTypography.titleMedium.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            InfoBadge(text = tag)
                            if (isActiveGameRunning) {
                                Spacer(Modifier.width(8.dp))
                                InfoBadge(text = "RUNNING", color = RPCSXColors.errorColor)
                            }
                        } else if (!isGridView) {
                            when (currentItem) {
                                is PagerItem.SourceCandidate -> {
                                    Spacer(Modifier.width(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(16.dp)
                                            .background(RPCSXColors.outlineVariant.copy(alpha = 0.6f))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = currentItem.displayName.uppercase(),
                                        style = AppTypography.titleMedium.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        softWrap = false,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    currentItem.titleId?.let { id ->
                                        Spacer(Modifier.width(8.dp))
                                        InfoBadge(text = id)
                                    }
                                }
                                is PagerItem.PendingImport -> {
                                    Spacer(Modifier.width(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(16.dp)
                                            .background(RPCSXColors.outlineVariant.copy(alpha = 0.6f))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = (currentItem.displayName ?: currentItem.provisionalTitleId ?: "IMPORTING").uppercase(),
                                        style = AppTypography.titleMedium.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        softWrap = false,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    InfoBadge(text = "IMPORTING")
                                }
                                else -> {}
                            }
                        }
                    }
                }

                // Right: Search Bar, View Toggle, Import Game Button, Clock & Settings
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MinimalSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        isExpanded = isSearchExpanded,
                        onExpandedChange = { isSearchExpanded = it },
                        ambientModel = fullscreenAmbientModel
                    )

                    ImportTopBarButton(
                        onClick = { showImportDialog = true },
                        ambientModel = fullscreenAmbientModel
                    )

                    Text(
                        SimpleDateFormat("h:mm a", configuration.locales[0]).format(Date()),
                        style = AppTypography.labelMedium,
                        color = Color.White.copy(alpha = 0.92f)
                    )

                    AdaptiveBatteryIndicator()

                    FrostedGlassBox(
                        ambientModel = fullscreenAmbientModel,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, RPCSXColors.surfaceOverlay),
                        modifier = Modifier
                            .clickable { navigateToSettings?.invoke() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ControllerGlyphBadge(glyph = "START")
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = "Settings",
                                tint = RPCSXColors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (visibleGames.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    FrostedGlassBox(
                        ambientModel = fullscreenAmbientModel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.4f)),
                        alignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.no_games_yet),
                                style = AppTypography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                textAlign = TextAlign.Center,
                                color = RPCSXColors.textPrimary,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { showImportDialog = true },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = RPCSXColors.primary,
                                        contentColor = RPCSXColors.background,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.import_game_action),
                                        style = AppTypography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                OutlinedButton(
                                    onClick = { folderPickerLauncher.launch(null) },
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, RPCSXColors.primary),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = RPCSXColors.primary,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.game_folder_scan_action),
                                        style = AppTypography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (filteredGames.isEmpty() && searchQuery.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FrostedGlassBox(
                        ambientModel = fullscreenAmbientModel,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RPCSXColors.surfaceOverlay),
                        alignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp)
                        ) {
                            Text(
                                text = "NO GAMES MATCHING \"$searchQuery\"",
                                style = AppTypography.headlineMedium.copy(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = RPCSXColors.textSecondary,
                                textAlign = TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = { searchQuery = "" },
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, RPCSXColors.primary),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = RPCSXColors.primary
                                )
                            ) {
                                Text("CLEAR SEARCH", style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                }
            } else if (isGridView) {
                GamesGridView(
                    games = filteredGames,
                    focusedIndex = focusedGridIndex,
                    onFocusChange = { focusedGridIndex = it },
                    onPlayGame = { game ->
                        if (game.info.path != "$" && game.findProgress(GameProgressType.Install) == null) {
                            launchCenterGame = game
                        }
                    },
                    onConfigureGame = { game -> configureGameTarget = game },
                    emulatorActiveGame = emulatorActiveGame.value,
                    gameplayRunning = gameplayRunning,
                    isTablet = isTablet,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else if (isTablet) {
                TabletHomeScreen(
                    pagerItems = pagerItems,
                    pagerState = pagerState,
                    currentItem = currentItem,
                    onPlayGame = { game ->
                        if (game.info.path != "$" && game.findProgress(GameProgressType.Install) == null) {
                            launchCenterGame = game
                        }
                    },
                    onConfigureGame = { game -> configureGameTarget = game },
                    onImportGame = { showImportDialog = true },
                    onInstallFirmware = { installFwLauncher?.launch("*/*") },
                    emulatorActiveGame = emulatorActiveGame.value,
                    gameplayRunning = gameplayRunning,
                    installPpu = installPpu,
                    prelaunchPpu = prelaunchPpu,
                    runtimePpu = runtimePpu,
                    fullscreenAmbientModel = fullscreenAmbientModel,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // On wide/landscape screens maxHeight is small, so drive size from the
                    // smaller of width and height to keep cards a reasonable, readable size.
                    val isLandscape = maxWidth > maxHeight
                    val itemSize = if (isLandscape) {
                        // Sized so focused card (scaled 1.12x) fits with >20dp clearance from top and bottom bars
                        val maxAllowedHeight = (maxHeight - 36.dp) / 1.15f
                        maxAllowedHeight.coerceIn(140.dp, 360.dp)
                    } else {
                        (maxHeight - 48.dp) * 0.85f
                    }
                    val itemHeight = itemSize
                    val itemWidth = itemHeight * (if (isLandscape) 0.75f else 0.85f)
                    val horizontalPadding = if (maxWidth > itemWidth) (maxWidth - itemWidth) / 2 else 0.dp
                    val verticalPadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(16.dp)
                    val coroutineScope = rememberCoroutineScope()

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().scale(bootScale),
                        contentPadding = PaddingValues(
                            horizontal = horizontalPadding,
                            vertical = verticalPadding
                        ),
                        pageSpacing = 24.dp,
                        verticalAlignment = Alignment.CenterVertically,
                        key = { idx -> pagerItems.getOrNull(idx)?.stableKey ?: "page:$idx" }
                    ) { page ->
                        val distance = abs(page - pagerState.currentPage)
                        val item = pagerItems.getOrNull(page) ?: return@HorizontalPager
                        when (item) {
                            is PagerItem.GameItem -> {
                                GameCard(
                                    game = item.game,
                                    distance = distance,
                                    ambientBgModel = fullscreenAmbientModel,
                                    onClick = {
                                        if (distance == 0) {
                                            val g = item.game
                                            if (g.info.path != "$" && g.findProgress(GameProgressType.Install) == null) {
                                                launchCenterGame = g
                                            }
                                        } else {
                                            coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                        }
                                    },
                                    onPlay = {
                                        val g = item.game
                                        if (g.info.path == "$" || g.findProgress(GameProgressType.Install) != null) return@GameCard
                                        launchCenterGame = g
                                    },
                                    isRunning = gameplayRunning && emulatorActiveGame.value == item.game.info.path,
                                    onConfigure = { configureGameTarget = item.game }
                                )
                            }
                            is PagerItem.AddGame -> {
                                AddGameCard(
                                    distance = distance,
                                    onClick = if (item.disabled) ({}) else ({ showImportDialog = true }),
                                    disabled = item.disabled
                                )
                            }
                            is PagerItem.FirmwareCard -> {
                                FirmwareCard(
                                    distance = distance,
                                    onClick = { installFwLauncher?.launch("*/*") }
                                )
                            }
                            is PagerItem.SourceCandidate -> { }
                            is PagerItem.PendingImport -> {
                                PendingImportCard(
                                    item = item,
                                    distance = distance,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(page) } }
                                )
                            }
                        }
                    }
                }
            }

            // Hint Strip (Frosted Glass Bottom Bar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(bootAlpha)
                    .clipToBounds()
            ) {
                if (fullscreenAmbientModel != null) {
                    AsyncImage(
                        model = fullscreenAmbientModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.BottomCenter,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(radius = 16.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF141009).copy(alpha = 0.65f),
                                    Color(0xFF141009).copy(alpha = 0.85f)
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawLine(
                                color = Color.White.copy(alpha = 0.2f),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, 0f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                val fwVersion by remember { FirmwareRepository.version }
                val fwProgressId by remember { FirmwareRepository.progressChannel }
                val fwProgressEntry = ProgressRepository.getItem(fwProgressId)?.value
                val fwProgressMessage = fwProgressEntry?.message?.value
                val isFwInstalling = fwProgressId != null

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (installPpu.ppuActive) {
                        Text(
                            text = stringResource(R.string.compiling_ppu_title),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.primary
                        )
                        val installFrac = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileBarFraction(installPpu)
                        if (installFrac != null) {
                            LinearProgressIndicator(
                                progress = { installFrac },
                                modifier = Modifier
                                    .widthIn(min = 80.dp, max = 200.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .widthIn(min = 80.dp, max = 200.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        }
                        Text(
                            text = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(installPpu),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else if (prelaunchPpu.ppuActive) {
                        Text(
                            text = "Preparing PPU",
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.primary
                        )
                        LinearProgressIndicator(
                            progress = { (prelaunchPpu.ppuPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .widthIn(min = 80.dp, max = 200.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = RPCSXColors.primary,
                            trackColor = RPCSXColors.surfaceOverlay,
                        )
                        Text(
                            text = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(prelaunchPpu),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else if (runtimePpu.ppuActive) {
                        Text(
                            text = stringResource(R.string.compiling_ppu_title),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.primary
                        )
                        LinearProgressIndicator(
                            progress = { (runtimePpu.ppuPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .widthIn(min = 80.dp, max = 200.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = RPCSXColors.primary,
                            trackColor = RPCSXColors.surfaceOverlay,
                        )
                        Text(
                            text = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(runtimePpu),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else if (isPackageInstalling) {
                        Text(
                            text = stringResource(R.string.package_installation),
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.primary
                        )
                        val installVal = activeInstallEntry?.value?.longValue ?: 0L
                        val installMax = activeInstallEntry?.max?.longValue ?: 0L
                        if (installMax > 0) {
                            LinearProgressIndicator(
                                progress = { (installVal.toFloat() / installMax.toFloat()).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .widthIn(min = 80.dp, max = 200.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .widthIn(min = 80.dp, max = 200.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = RPCSXColors.primary,
                                trackColor = RPCSXColors.surfaceOverlay,
                            )
                        }
                        activeInstallEntry?.message?.value?.let {
                            Text(
                                text = it,
                                style = AppTypography.labelSmall,
                                color = RPCSXColors.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    } else if (fwVersion != null) {
                        Text(
                            text = stringResource(R.string.firmware) + " " + fwVersion,
                            style = AppTypography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f)
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
                                        .widthIn(min = 80.dp, max = 200.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = RPCSXColors.primary,
                                    trackColor = RPCSXColors.surfaceOverlay,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .widthIn(min = 80.dp, max = 200.dp)
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IsoFoldersTopBarButton(
                        folders = scannedFolders,
                        ambientModel = fullscreenAmbientModel,
                        expanded = isFoldersExpanded,
                        onExpandedChange = { isFoldersExpanded = it },
                        onAddFolder = { folderPickerLauncher.launch(null) },
                        onRemoveFolder = { treeUri -> ScannedFoldersRepository.remove(context, treeUri) },
                    )
                    RefreshFoldersTopBarButton(
                        enabled = scannedFolders.isNotEmpty() && !scanningFolder,
                        onClick = triggerRefresh,
                    )
                    Box(
                        modifier = Modifier.clickable {
                            isGridView = !isGridView
                            com.zenithblue.sambas3.utils.GeneralSettings.setValue("home_view_mode", if (isGridView) "grid" else "carousel")
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Image(
                                painter = painterResource(R.drawable.square),
                                contentDescription = "Square",
                                modifier = Modifier.size(14.dp),
                            )
                            Icon(
                                painter = painterResource(if (isGridView) R.drawable.ic_menu else R.drawable.ic_grid_on),
                                contentDescription = if (isGridView) "Switch to carousel view" else "Switch to grid view",
                                tint = if (isGridView) RPCSXColors.primary else RPCSXColors.textSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    if (currentItem is PagerItem.FirmwareCard) {
                        HintButton(text = "INSTALL", icon = "X", color = RPCSXColors.primary, onClick = { installFwLauncher?.launch("*/*") })
                    } else if (currentItem is PagerItem.AddGame) {
                        if (currentItem.disabled) {
                            HintButton(text = "WAITING", icon = "X", color = RPCSXColors.textDisabled, onClick = { })
                        } else {
                            HintButton(text = "ADD", icon = "X", color = RPCSXColors.primary, onClick = { showImportDialog = true })
                        }
                    } else if (stopInProgress) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RPCSXColors.errorColor, strokeWidth = 2.dp)
                            HintButton(text = "STOPPING...", icon = "■", color = RPCSXColors.textDisabled, onClick = { })
                        }
                    } else if (gameplayRunning) {
                        val stopScope = rememberCoroutineScope()
                        HintButton(text = "STOP", icon = "■", color = RPCSXColors.errorColor, onClick = {
                            stopScope.launch {
                                com.zenithblue.sambas3.ppu.GameStopHelper.stopGameplay(context)
                            }
                        })
                    } else {
                        val hintGame = if (isGridView) {
                            filteredGames.getOrNull(focusedGridIndex) ?: filteredGames.firstOrNull()
                        } else {
                            (currentItem as? PagerItem.GameItem)?.game
                        }
                        val hintTitleId = hintGame?.let {
                            runCatching {
                                com.zenithblue.sambas3.GameIdentity.titleIdOrNull(it.info.path, it.info.name.value)
                            }.getOrNull()
                        }
                        val hintAvailability = hintGame?.let {
                            com.zenithblue.sambas3.ppu.GameRunEligibilityHelper.evaluateAvailability(
                                context, it, installPpu.ppuActive, prelaunchPpu, runtimePpu, emulatorState.value, emulatorActiveGame.value
                            )
                        }
                        val hintPpuUi = hintGame?.let { game ->
                            com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.build(
                                hintTitleId,
                                hintAvailability ?: com.zenithblue.sambas3.ppu.GameLaunchAvailability.NeedsPreparation,
                                com.zenithblue.sambas3.ui.games.launch.LaunchRuntimeInputs(
                                    installPpu = installPpu,
                                    prelaunchPpu = prelaunchPpu,
                                    runtimePpu = runtimePpu,
                                    emulatorState = emulatorState.value,
                                    activeGame = emulatorActiveGame.value,
                                    waitingForIdle = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.waitingForIdle,
                                    deferredForFgs = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.deferredForFgs,
                                    fgsStartDenied = CompileProgressBridge.fgsStartDenied,
                                    preRuntimeState = hintTitleId?.let {
                                        runCatching { PpuReadinessStore.getPreRuntimeState(context, it) }
                                            .getOrDefault(PreRuntimePpuState.NOT_DONE)
                                    } ?: PreRuntimePpuState.NOT_DONE,
                                    runtimeReadyState = hintTitleId?.let {
                                        runCatching { PpuReadinessStore.getRuntimeState(context, it) }
                                            .getOrDefault(RuntimePpuState.NOT_STARTED)
                                    } ?: RuntimePpuState.NOT_STARTED,
                                    validatedByRealBootFrame = hintTitleId?.let {
                                        runCatching { PpuReadinessStore.isRuntimeValidated(context, it) }.getOrDefault(false)
                                    } ?: false,
                                    activeCompileTitleId = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.activeTitleId,
                                    stoppingCompile = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.stopping,
                                ),
                            )
                        }
                        when (hintPpuUi?.prepareAction) {
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.Stop -> {
                                HintButton(text = "STOP PPU", icon = "■", color = RPCSXColors.errorColor, onClick = {
                                    com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestStop(context)
                                })
                            }
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.Stopping -> {
                                HintButton(text = "STOPPING...", icon = "■", color = RPCSXColors.textDisabled, onClick = { })
                            }
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.Locked -> {
                                HintButton(text = "WAITING", icon = "X", color = RPCSXColors.textDisabled, onClick = { })
                            }
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.Prepare -> {
                                HintButton(text = "PREPARE PPU", icon = "X", color = RPCSXColors.primary, onClick = {
                                    hintGame?.let {
                                        com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, it)
                                    }
                                })
                            }
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.Retry -> {
                                HintButton(text = "RETRY PPU", icon = "X", color = RPCSXColors.errorColor, onClick = {
                                    hintGame?.let {
                                        com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, it)
                                    }
                                })
                            }
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.PreparingInstall,
                            com.zenithblue.sambas3.ui.games.launch.PrepareAction.PreparingRuntime -> {
                                HintButton(text = "PREPARING", icon = "X", color = RPCSXColors.textDisabled, onClick = { })
                            }
                            null -> when (hintAvailability) {
                                is com.zenithblue.sambas3.ppu.GameLaunchAvailability.PreparingPpu,
                                is com.zenithblue.sambas3.ppu.GameLaunchAvailability.WaitingForEngineIdle,
                                is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Importing,
                                is com.zenithblue.sambas3.ppu.GameLaunchAvailability.EngineBusy -> {
                                    HintButton(text = "PREPARING", icon = "X", color = RPCSXColors.textDisabled, onClick = { })
                                }
                                is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready -> {
                                    HintButton(text = "PLAY", icon = "X", color = RPCSXColors.primary, onClick = { launchCenterGame = hintGame })
                                }
                                else -> {
                                    val isPlayable = hintAvailability == null || hintAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready
                                    HintButton(
                                        text = "PLAY",
                                        icon = "X",
                                        color = if (isPlayable || hintGame == null) RPCSXColors.primary else RPCSXColors.textDisabled,
                                        onClick = {
                                            if (hintGame != null && hintAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready) {
                                                launchCenterGame = hintGame
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        // Floating Crash Recovery / Stop Failure Banner
        if (recoveryState !is HomeRecoveryState.None) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .align(Alignment.TopCenter)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                CrashRecoveryCard(
                    state = recoveryState,
                    onContinueSave = {
                        val latest = recoveryGame?.let {
                            GameSavestateRepository.slots(context, it)
                                .filter { save -> save.exists && save.path != null }
                                .maxByOrNull { save -> save.mtimeMs }
                        }
                        launchRecovery(recoveryGame, latest?.path, latest?.slot, RecoveryAction.ContinueSave)
                    },
                    onRetry = {
                        val failure = recoveryState as? HomeRecoveryState.LoadFailure
                        launchRecovery(
                            recoveryGame,
                            failure?.savestatePath,
                            failure?.slot,
                            if (failure != null) RecoveryAction.Retry else RecoveryAction.Retry,
                        )
                    },
                    onPlayFresh = { launchRecovery(recoveryGame, null, null, RecoveryAction.PlayFresh) },
                    onChooseSave = {
                        if (recoveryGame != null) launchCenterGame = recoveryGame
                        else HomeRecoveryRepository.markActionFailed(context, recoverySession, "Choose a save from the library")
                    },
                    onDetails = { detailsState = recoveryState },
                    onViewLogs = { navigateToCrashLogs?.invoke(recoverySessionId(recoveryState)) ?: navigateToLogs?.invoke(recoverySessionId(recoveryState)) },
                    onOpenAllCrashLogs = { navigateToCrashLogs?.invoke(recoverySessionId(recoveryState)) ?: navigateToLogs?.invoke(recoverySessionId(recoveryState)) },
                    onDismiss = { HomeRecoveryRepository.dismiss(context) },
                )
            }
        }
        (stopState as? com.zenithblue.sambas3.session.EmulatorStopState.Failed)?.let { failedStop ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .align(Alignment.TopCenter)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                StopFailureCard(
                    state = failedStop,
                    onRecheck = {
                        recoveryScope.launch {
                            EmulatorStopCoordinator.stop(context, failedStop.reason)
                        }
                    },
                    onViewLogs = { navigateToCrashLogs?.invoke(com.zenithblue.sambas3.logging.LogBroker.currentSessionId) ?: navigateToLogs?.invoke(com.zenithblue.sambas3.logging.LogBroker.currentSessionId) },
                    onForceClose = {
                        Log.e("S3STOP", "user requested force-close requestId=${failedStop.requestId}")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            }
        }

        patchTitleId?.let { titleId ->
            com.zenithblue.sambas3.ui.settings.PatchManagerScreen(
                navigateBack = { patchTitleId = null },
                titleId = titleId,
            )
        }

        launchCenterGame?.let { game ->
            val readinessRevision by PpuReadinessStore.revision.collectAsState()
            val coordinatorRevision by com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.coordinatorRevision.collectAsState()
            val titleId = com.zenithblue.sambas3.GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
            // Readiness + compile flows must participate in composition so START enables without reopen.
            @Suppress("UNUSED_VARIABLE")
            val _rev = readinessRevision + coordinatorRevision
            val launchInputs = com.zenithblue.sambas3.ui.games.launch.LaunchRuntimeInputs(
                installPpu = installPpu,
                prelaunchPpu = prelaunchPpu,
                runtimePpu = runtimePpu,
                emulatorState = emulatorState.value,
                activeGame = emulatorActiveGame.value,
                waitingForIdle = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.waitingForIdle,
                deferredForFgs = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.deferredForFgs,
                fgsStartDenied = CompileProgressBridge.fgsStartDenied,
                preRuntimeState = titleId?.let {
                    runCatching { PpuReadinessStore.getPreRuntimeState(context, it) }
                        .getOrDefault(PreRuntimePpuState.NOT_DONE)
                } ?: PreRuntimePpuState.NOT_DONE,
                runtimeReadyState = titleId?.let {
                    runCatching { PpuReadinessStore.getRuntimeState(context, it) }
                        .getOrDefault(RuntimePpuState.NOT_STARTED)
                } ?: RuntimePpuState.NOT_STARTED,
                validatedByRealBootFrame = titleId?.let {
                    runCatching { PpuReadinessStore.isRuntimeValidated(context, it) }.getOrDefault(false)
                } ?: false,
                activeCompileTitleId = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.activeTitleId,
                stoppingCompile = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.stopping,
            )
            GameLaunchCenter(
                snapshot = GameLaunchRepository.snapshot(context, game, launchInputs),
                onDismiss = { launchCenterGame = null },
                onFreshPlay = {
                    val action = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator
                        .requestPreparation(context, game)
                    if (com.zenithblue.sambas3.ppu.PpuUserActionDecision.canEnterRealBoot(action)) {
                        launchCenterGame = null
                        bootingGame = game
                    }
                },
                onContinue = { slot ->
                    launchCenterGame = null
                    bootGame(context, game, slot.path?.takeIf { slot.exists }, slot.slot)
                },
                onLoad = { slot ->
                    launchCenterGame = null
                    bootGame(context, game, slot.path?.takeIf { slot.exists }, slot.slot)
                },
                onConfigure = {
                    // Keep the launcher composed underneath: the config page opens
                    // in front of it and closing returns to the launcher.
                    configureGameTarget = game
                },
                onDriver = {
                    launchCenterGame = null
                    (navigateToDrivers ?: navigateToSettings)?.invoke()
                },
                onPatches = {
                    patchTitleId = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
                    launchCenterGame = null
                },
                onAchievements = {
                    stoppedTrophiesLoading = true
                    stoppedTrophies = null
                },
                onClearCache = {
                    clearCacheTarget = game
                },
                canClearCache = !gameplayRunning &&
                    !installPpu.ppuActive &&
                    !prelaunchPpu.ppuActive &&
                    !runtimePpu.ppuActive &&
                    !com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.stopping &&
                    !com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.hasActiveOwner(),
                onPrepare = {
                    com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, game)
                },
                onStop = {
                    com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestStop(context)
                },
                showTrophies = stoppedTrophiesLoading || stoppedTrophies != null,
                trophiesData = stoppedTrophies,
                trophiesLoading = stoppedTrophiesLoading,
                onDismissTrophies = {
                    stoppedTrophiesLoading = false
                    stoppedTrophies = null
                },
            )
        }

        detailsState?.let { state ->
            val session = when (state) {
                is HomeRecoveryState.ConfirmedCrash -> state.session
                is HomeRecoveryState.Interrupted -> state.session
                is HomeRecoveryState.ActionFailed -> state.session
                else -> null
            }
            val report = when (state) {
                is HomeRecoveryState.ConfirmedCrash -> state.report
                is HomeRecoveryState.Interrupted -> state.report
                is HomeRecoveryState.LoadFailure -> state.report
                else -> null
            }
            val failure = (state as? HomeRecoveryState.LoadFailure)?.reason
            CrashDetailsSheet(
                session = session,
                initialReport = report,
                loadFailure = failure,
                onChooseSave = {
                    detailsState = null
                    if (recoveryGame != null) launchCenterGame = recoveryGame
                },
                onSafeRetry = { safeRetryRecovery(state) },
                onViewLogs = { navigateToCrashLogs?.invoke(recoverySessionId(state)) ?: navigateToLogs?.invoke(recoverySessionId(state)) },
                onOpenAllCrashLogs = {
                    detailsState = null
                    navigateToCrashLogs?.invoke(recoverySessionId(state)) ?: navigateToLogs?.invoke(recoverySessionId(state))
                },
                onExportReport = { exportRecoveryReport(state) },
                onDismiss = { detailsState = null },
            )
        }

        if (configureGameTarget != null) {
            // Fullscreen config page over a platform-blurred library backdrop
            // (blur is Android 12+; the page scrim keeps it readable below that).
            Dialog(
                onDismissRequest = { configureGameTarget = null },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                DialogBackgroundBlur(radius = 32)
                DialogImmersiveSystemBars()
                GameConfigureScreen(
                    gamePath = configureGameTarget?.info?.path,
                    modifier = Modifier.fillMaxSize(),
                    onClose = { configureGameTarget = null },
                    onRemove = {
                        removeGameTarget = configureGameTarget
                        configureGameTarget = null
                    },
                )
            }
        }

        if (removeGameTarget != null) {
            val target = removeGameTarget
            AlertDialog(
                onDismissRequest = { if (!removingGame) removeGameTarget = null },
                title = { Text(stringResource(R.string.remove_game)) },
                text = {
                    Text(
                        stringResource(
                            R.string.remove_game_confirmation,
                            target?.info?.name?.value
                                ?: target?.info?.path?.substringAfterLast('/')
                                ?: "game",
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !removingGame,
                        onClick = {
                            val game = removeGameTarget ?: return@TextButton
                            removingGame = true
                            FileUtil.removeGame(context, game) { success ->
                                removingGame = false
                                removeGameTarget = null
                                if (!success) removeGameFailed = true
                            }
                        },
                    ) {
                        if (removingGame) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = RPCSXColors.primary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.remove_game))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !removingGame,
                        onClick = { removeGameTarget = null },
                    ) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        if (removeGameFailed) {
            AlertDialog(
                onDismissRequest = { removeGameFailed = false },
                title = { Text(stringResource(R.string.error)) },
                text = { Text(stringResource(R.string.remove_game_failed)) },
                confirmButton = {
                    TextButton(onClick = { removeGameFailed = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        if (clearCacheTarget != null) {
            val target = clearCacheTarget
            AlertDialog(
                onDismissRequest = { if (!clearingCache) clearCacheTarget = null },
                title = { Text(stringResource(R.string.clear_game_cache)) },
                text = {
                    Text(
                        stringResource(
                            R.string.clear_game_cache_warning,
                            target?.info?.name?.value
                                ?: target?.info?.path?.substringAfterLast('/')
                                ?: "game",
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !clearingCache,
                        onClick = {
                            val game = clearCacheTarget ?: return@TextButton
                            val title = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
                            val busy = gameplayRunning ||
                                CompileProgressBridge.installState.value.ppuActive ||
                                CompileProgressBridge.prelaunchState.value.ppuActive ||
                                CompileProgressBridge.state.value.ppuActive ||
                                com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.stopping ||
                                com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.hasActiveOwner()
                            if (title == null || busy) {
                                clearCacheTarget = null
                                clearCacheFailed = true
                                return@TextButton
                            }
                            clearingCache = true
                            recoveryScope.launch(Dispatchers.IO) {
                                val success = com.zenithblue.sambas3.utils.GameCacheManager.clear(context, title)
                                withContext(Dispatchers.Main) {
                                    clearingCache = false
                                    clearCacheTarget = null
                                    clearCacheFailed = !success
                                }
                            }
                        },
                    ) {
                        if (clearingCache) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = RPCSXColors.primary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.clear_game_cache))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !clearingCache,
                        onClick = { clearCacheTarget = null },
                    ) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }

        if (clearCacheFailed) {
            AlertDialog(
                onDismissRequest = { clearCacheFailed = false },
                title = { Text(stringResource(R.string.error)) },
                text = { Text(stringResource(R.string.clear_game_cache_failed)) },
                confirmButton = {
                    TextButton(onClick = { clearCacheFailed = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        if (showImportDialog) {
            ImportMethodDialog(
                onDismiss = { showImportDialog = false },
                onImportIso = {
                    showImportDialog = false
                    if (com.zenithblue.sambas3.BuildConfig.DIRECT_ISO_LOADING) {
                        directIsoPickerLauncher.launch(arrayOf("*/*"))
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.iso_direct_required),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        if (scanningFolder) {
            ScanningFoldersOverlay(
                ambientModel = fullscreenAmbientModel,
            )
        }

        folderImportResult?.let { result ->
            GameFolderScanDialog(
                result = result,
                ambientModel = fullscreenAmbientModel,
                onDismiss = { folderImportResult = null },
            )
        }
    }
}

private fun Modifier.gamepadClickable(onClick: () -> Unit): Modifier = this.onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown &&
        (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
         event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
         event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
    ) {
        onClick()
        true
    } else false
}

@Composable
private fun ScanningFoldersOverlay(
    ambientModel: Any? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .blur(70.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            RPCSXColors.primary.copy(alpha = 0.22f),
                            Color.Transparent,
                        )
                    ),
                    shape = CircleShape,
                )
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF20E1524),
            border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.35f)),
            modifier = Modifier
                .widthIn(min = 320.dp, max = 420.dp)
                .padding(16.dp),
            shadowElevation = 24.dp,
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (ambientModel != null) {
                    AsyncImage(
                        model = ambientModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(20.dp)
                            .alpha(0.35f),
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF00D121F),
                                    Color(0xF8080C14),
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(RPCSXColors.primary.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(38.dp),
                            color = RPCSXColors.primary,
                            strokeWidth = 3.dp,
                            trackColor = Color(0x22FFFFFF),
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = RPCSXColors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.game_folder_scan_title).uppercase(),
                            style = AppTypography.headlineSmall.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                            ),
                            color = RPCSXColors.textPrimary,
                        )
                        Text(
                            text = stringResource(R.string.game_folder_scanning),
                            style = AppTypography.bodySmall.copy(fontSize = 12.sp),
                            color = RPCSXColors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = RPCSXColors.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Indexing PS3 disc images and JB folder structures...",
                                style = AppTypography.labelSmall.copy(fontSize = 11.sp),
                                color = RPCSXColors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameFolderScanDialog(
    result: DirectIsoManager.IsoFolderImportResult,
    ambientModel: Any? = null,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var isDoneFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    BackHandler {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    when (code) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BUTTON_B,
                        KeyEvent.KEYCODE_BACK -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(360.dp)
                .blur(80.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            RPCSXColors.primary.copy(alpha = 0.18f),
                            Color.Transparent,
                        )
                    ),
                    shape = CircleShape,
                )
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF20E1524),
            border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.45f)),
            modifier = Modifier
                .widthIn(min = 400.dp, max = 540.dp)
                .fillMaxWidth(0.88f)
                .padding(16.dp),
            shadowElevation = 28.dp,
        ) {
            Box(Modifier.fillMaxWidth()) {
                if (ambientModel != null) {
                    AsyncImage(
                        model = ambientModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur(24.dp)
                            .alpha(0.35f),
                    )
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xF00F1524),
                                    Color(0xF8080C14),
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = RPCSXColors.primary.copy(alpha = 0.15f),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder),
                                        contentDescription = null,
                                        tint = RPCSXColors.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.game_folder_scan_title).uppercase(),
                                    style = AppTypography.headlineSmall.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    ),
                                    color = RPCSXColors.textPrimary,
                                )
                                Text(
                                    text = "Folder refresh finished",
                                    style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                                    color = RPCSXColors.textSecondary,
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (result.importedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0x2656D364),
                                    border = BorderStroke(1.dp, Color(0x5056D364)),
                                ) {
                                    Text(
                                        text = "+${result.importedCount} NEW",
                                        style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color(0xFF56D364),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (result.alreadyImportedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0x22E5A93C),
                                    border = BorderStroke(1.dp, Color(0x50E5A93C)),
                                ) {
                                    Text(
                                        text = "${result.alreadyImportedCount} INDEXED",
                                        style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color(0xFFE5A93C),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (result.failedCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0x26F85149),
                                    border = BorderStroke(1.dp, Color(0x50F85149)),
                                ) {
                                    Text(
                                        text = "${result.failedCount} FAILED",
                                        style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = Color(0xFFF85149),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (result.entries.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.White.copy(alpha = 0.04f),
                                border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check_circle),
                                        contentDescription = null,
                                        tint = RPCSXColors.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.game_folder_no_games),
                                        style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                        color = RPCSXColors.textPrimary,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = "All game directories are already indexed in your library.",
                                        style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                                        color = RPCSXColors.textSecondary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        } else {
                            result.entries.forEach { entry ->
                                val statusLabel: String
                                val statusColor: Color
                                val statusBg: Color
                                val statusIcon: Int
                                when (entry.status) {
                                    DirectIsoManager.IsoImportStatus.IMPORTED -> {
                                        statusLabel = stringResource(R.string.onboarding_iso_status_imported)
                                        statusColor = Color(0xFF56D364)
                                        statusBg = Color(0x2256D364)
                                        statusIcon = R.drawable.ic_check_circle
                                    }
                                    DirectIsoManager.IsoImportStatus.ALREADY_IMPORTED -> {
                                        statusLabel = stringResource(R.string.onboarding_iso_status_already)
                                        statusColor = Color(0xFFE5A93C)
                                        statusBg = Color(0x22E5A93C)
                                        statusIcon = R.drawable.ic_check_circle
                                    }
                                    DirectIsoManager.IsoImportStatus.FAILED -> {
                                        statusLabel = entry.message ?: stringResource(R.string.onboarding_iso_status_failed_generic)
                                        statusColor = RPCSXColors.errorColor
                                        statusBg = Color(0x26F85149)
                                        statusIcon = R.drawable.circle
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White.copy(alpha = 0.04f),
                                    border = BorderStroke(1.dp, Color(0x18FFFFFF)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(statusIcon),
                                            contentDescription = null,
                                            tint = statusColor,
                                            modifier = Modifier.size(18.dp),
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.displayName,
                                                style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                                color = RPCSXColors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            entry.titleId?.let { tid ->
                                                Text(
                                                    text = tid,
                                                    style = AppTypography.labelSmall.copy(fontSize = 10.sp),
                                                    color = RPCSXColors.textSecondary,
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = statusBg,
                                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f)),
                                        ) {
                                            Text(
                                                text = statusLabel.uppercase(),
                                                style = AppTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                color = statusColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDoneFocused) Color(0xFFFFCC00) else Color(0xFFFFB800),
                        border = if (isDoneFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0x60FFFFFF)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .onFocusChanged { isDoneFocused = it.isFocused }
                            .gamepadClickable(onDismiss),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.cross),
                                contentDescription = null,
                                tint = Color(0xFF0D1117),
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(android.R.string.ok).uppercase(),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color(0xFF0D1117),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoBadge(text: String, color: Color = RPCSXColors.textSecondary) {
    Surface(
        color = RPCSXColors.surfaceElevated,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, RPCSXColors.surfaceOverlay)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = AppTypography.labelMedium,
            color = color
        )
    }
}

data class BatteryUiState(val pct: Int, val charging: Boolean, val low: Boolean)

/**
 * Event-driven battery state: reads the cached ACTION_BATTERY_CHANGED sticky
 * broadcast once, then re-reads only when the system pushes a change.
 * No polling, no wakeups.
 */
@Composable
fun rememberAdaptiveBatteryState(): BatteryUiState {
    val context = LocalContext.current
    fun read(intent: Intent?): BatteryUiState {
        if (intent == null) return BatteryUiState(100, false, false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = if (level >= 0) level * 100 / scale else 100
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val low = intent.getIntExtra(BatteryManager.EXTRA_BATTERY_LOW, 0) != 0
        return BatteryUiState(pct, charging, low)
    }
    var state by remember {
        mutableStateOf(read(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))))
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { state = read(i) }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

@Composable
fun AdaptiveBatteryIndicator(modifier: Modifier = Modifier) {
    val state = rememberAdaptiveBatteryState()
    val color = when {
        state.charging -> RPCSXColors.focusRing
        state.low || state.pct <= 15 -> RPCSXColors.errorColor
        state.pct <= 30 -> RPCSXColors.primary
        else -> RPCSXColors.textPrimary
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 16.dp, height = 9.dp)) {
            val sw = 1.2.dp.toPx()
            val capW = 1.6.dp.toPx()
            val bodyW = size.width - capW - sw
            val bodyH = size.height - sw
            drawRoundRect(
                color,
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
                style = Stroke(sw)
            )
            drawRoundRect(
                color,
                topLeft = Offset(bodyW + sw / 2 + 0.8.dp.toPx(), size.height * 0.28f),
                size = Size(capW, size.height * 0.44f),
                cornerRadius = CornerRadius(0.5.dp.toPx()),
                style = Fill
            )
            val inner = sw * 1.2f
            val fillW = (bodyW - inner * 2) * (state.pct.coerceIn(0, 100) / 100f)
            if (fillW > 0f) {
                drawRoundRect(
                    color,
                    topLeft = Offset(inner, inner),
                    size = Size(fillW, bodyH - inner - sw * 0.4f),
                    cornerRadius = CornerRadius(0.5.dp.toPx()),
                    style = Fill
                )
            }
            if (state.charging) {
                val ix = inner
                val iy = inner
                val iw = bodyW - inner * 2
                val ih = bodyH - inner * 2
                val bolt = Path().apply {
                    moveTo(ix + iw * 0.58f, iy + ih * 0.02f)
                    lineTo(ix + iw * 0.28f, iy + ih * 0.56f)
                    lineTo(ix + iw * 0.50f, iy + ih * 0.56f)
                    lineTo(ix + iw * 0.42f, iy + ih * 0.98f)
                    lineTo(ix + iw * 0.74f, iy + ih * 0.38f)
                    lineTo(ix + iw * 0.52f, iy + ih * 0.38f)
                    close()
                }
                drawPath(bolt, RPCSXColors.background, style = Fill)
            }
        }
        Text(
            text = "${state.pct}%",
            style = AppTypography.labelMedium,
            color = color
        )
    }
}

@Composable
fun FrostedGlassBox(
    ambientModel: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    border: BorderStroke? = null,
    alignment: Alignment = Alignment.TopEnd,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
    ) {
        if (ambientModel != null) {
            AsyncImage(
                model = ambientModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier
                    .matchParentSize()
                    .blur(radius = 16.dp)
                    .alpha(0.95f)
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF0C101D).copy(alpha = 0.65f),
                            Color(0xFF0C101D).copy(alpha = 0.85f)
                        )
                    )
                )
        )
        content()
    }
}

@Composable
fun ImportTopBarButton(
    onClick: () -> Unit,
    ambientModel: Any? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(120)
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(4.dp)
    ) {
        // Diffuse tube-TV gold glow outline bloom (design_3.md focus-glow)
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(radius = 6.dp)
                .border(
                    BorderStroke(1.5.dp, RPCSXColors.focusGlow.copy(alpha = 0.65f)),
                    RoundedCornerShape(4.dp)
                )
        )

        // Frosted glass foreground container per design_3.md (surface-elevated + gold border)
        FrostedGlassBox(
            ambientModel = ambientModel,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, RPCSXColors.primary)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                // Controller mapping indicator: SELECT button pill
                Surface(
                    color = RPCSXColors.primaryMuted,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, RPCSXColors.primary.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "SELECT",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = AppTypography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        ),
                        color = RPCSXColors.primary
                    )
                }

                Text(
                    text = "IMPORT",
                    style = AppTypography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = RPCSXColors.textPrimary
                )
            }
        }
    }
}

@Composable
private fun IsoFoldersTopBarButton(
    folders: List<com.zenithblue.sambas3.utils.ScannedFolder>,
    ambientModel: Any? = null,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    var buttonHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val popupGapPx = with(density) { 8.dp.roundToPx() }
    Box(modifier = Modifier.onSizeChanged { buttonHeightPx = it.height }) {
        Box(
            modifier = Modifier.clickable { onExpandedChange(!expanded) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.l2),
                    contentDescription = "L2",
                    modifier = Modifier.size(15.dp),
                )
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
                    contentDescription = stringResource(R.string.iso_folders),
                    tint = RPCSXColors.primary,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = stringResource(R.string.iso_folders).uppercase(),
                    style = AppTypography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    ),
                    color = RPCSXColors.textPrimary,
                )
                if (folders.isNotEmpty()) {
                    Text(
                        text = folders.size.toString(),
                        style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = RPCSXColors.primary,
                    )
                }
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_keyboard_arrow_down else R.drawable.ic_keyboard_arrow_up),
                    contentDescription = null,
                    tint = RPCSXColors.textSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -(buttonHeightPx + popupGapPx)),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                val menuShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .widthIn(min = 240.dp, max = 340.dp)
                        .clip(menuShape)
                        .border(BorderStroke(1.dp, Color(0x33FFFFFF)), menuShape)
                ) {
                    if (ambientModel != null) {
                        AsyncImage(
                            model = ambientModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.BottomStart,
                            modifier = Modifier
                                .matchParentSize()
                                .blur(radius = 28.dp)
                                .alpha(0.95f),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xCC0C101D),
                                        Color(0xE60C101D),
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.iso_folders).uppercase(),
                                style = AppTypography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                ),
                                color = RPCSXColors.primary,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.l2),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = "CLOSE",
                                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                                    color = RPCSXColors.textSecondary,
                                )
                            }
                        }
                        if (folders.isEmpty()) {
                            Text(
                                text = stringResource(R.string.iso_folders_empty),
                                color = RPCSXColors.textSecondary,
                                style = AppTypography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        } else {
                            folders.forEach { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder),
                                        contentDescription = null,
                                        tint = RPCSXColors.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = folder.displayName,
                                        color = RPCSXColors.textPrimary,
                                        style = AppTypography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.iso_folders_remove),
                                        tint = RPCSXColors.errorColor,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable { onRemoveFolder(folder.treeUri) },
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color.Transparent,
                                            RPCSXColors.primary.copy(alpha = 0.45f),
                                            Color.Transparent,
                                        )
                                    )
                                )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onExpandedChange(false)
                                    onAddFolder()
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = null,
                                tint = RPCSXColors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = stringResource(R.string.iso_folders_add),
                                color = RPCSXColors.primary,
                                style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshFoldersTopBarButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.r2),
                contentDescription = "R2",
                modifier = Modifier
                    .size(15.dp)
                    .alpha(if (enabled) 1f else 0.55f),
            )
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.iso_folders_refresh),
                tint = if (enabled) RPCSXColors.primary else RPCSXColors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
fun ControllerGlyphBadge(glyph: String, color: Color = RPCSXColors.primary) {
    Surface(
        shape = RoundedCornerShape(3.dp),
        color = RPCSXColors.primaryMuted,
        border = BorderStroke(1.dp, color.copy(alpha = 0.7f))
    ) {
        Text(
            text = glyph,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            style = AppTypography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = color
        )
    }
}

@Composable
fun MinimalSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    ambientModel: Any? = null,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    if (isExpanded) {
        FrostedGlassBox(
            ambientModel = ambientModel,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, RPCSXColors.primary),
            modifier = modifier.height(34.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                ControllerGlyphBadge(glyph = "△")

                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = RPCSXColors.primary,
                    modifier = Modifier.size(15.dp)
                )

                Box(
                    modifier = Modifier
                        .widthIn(min = 90.dp, max = 190.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "SEARCH...",
                            style = AppTypography.labelSmall.copy(
                                fontSize = 11.sp,
                                letterSpacing = 0.8.sp,
                                color = RPCSXColors.textSecondary.copy(alpha = 0.7f)
                            )
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = AppTypography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RPCSXColors.textPrimary
                        ),
                        cursorBrush = SolidColor(RPCSXColors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { /* done */ })
                    )
                }

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Clear",
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = { onExpandedChange(false) },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Close search",
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    } else {
        FrostedGlassBox(
            ambientModel = ambientModel,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, RPCSXColors.surfaceOverlay),
            modifier = modifier
                .clickable { onExpandedChange(true) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ControllerGlyphBadge(glyph = "△")
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = RPCSXColors.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
fun GamesGridView(
    games: List<Game>,
    focusedIndex: Int,
    onFocusChange: (Int) -> Unit,
    onPlayGame: (Game) -> Unit,
    onConfigureGame: (Game) -> Unit,
    emulatorActiveGame: String?,
    gameplayRunning: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(focusedIndex) {
        if (focusedIndex in games.indices) {
            try { gridState.animateScrollToItem(focusedIndex) } catch (_: Exception) {}
        }
    }

    val columns = if (isTablet) 5 else 4
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = games,
            key = { _, game -> GameIdentity.key(game.info.path, game.info.name.value) }
        ) { index, game ->
            val isFocused = index == focusedIndex
            GridGameCard(
                game = game,
                isFocused = isFocused,
                isRunning = gameplayRunning && emulatorActiveGame == game.info.path,
                onClick = {
                    onFocusChange(index)
                    onPlayGame(game)
                },
                onConfigure = {
                    onFocusChange(index)
                    onConfigureGame(game)
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridGameCard(
    game: Game,
    isFocused: Boolean,
    isRunning: Boolean,
    onClick: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rawIconPath = game.info.iconPath.value
    val installedPreview = remember(rawIconPath) { GamePreviewRepository.resolveInstalledPreview(rawIconPath) }
    val coilModel: Any? = when (installedPreview) {
        is GamePreviewModel.LocalFile -> installedPreview.file
        is GamePreviewModel.ContentUri -> installedPreview.uri
        is GamePreviewModel.None -> null
    }
    val bgPreview by androidx.compose.runtime.produceState<Any?>(
        initialValue = null,
        key1 = game.info.path,
        key2 = rawIconPath
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (val bg = GamePreviewRepository.resolveBackground(context, game)) {
                is GamePreviewModel.LocalFile -> bg.file
                is GamePreviewModel.ContentUri -> bg.uri
                is GamePreviewModel.None -> null
            }
        }
    }
    val cardBgModel = bgPreview ?: coilModel

    val title = GameIdentity.displayName(game.info.path, game.info.name.value).uppercase()
    val tag = game.info.path.substringAfterLast("/")

    val scale by animateFloatAsState(if (isFocused) 1.04f else 1.0f, animationSpec = tween(200))

    val glowIntensity by animateDpAsState(
        targetValue = if (isFocused) 16.dp else 0.dp,
        animationSpec = tween(200)
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = RPCSXColors.surface,
        border = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) RPCSXColors.focusRing else RPCSXColors.surfaceOverlay
        ),
        shadowElevation = glowIntensity,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onConfigure
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Artwork container (16:9)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(RPCSXColors.background)
            ) {
                if (cardBgModel != null) {
                    // Blurred background artwork inside card
                    AsyncImage(
                        model = cardBgModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.15f)
                            .blur(radius = 16.dp)
                            .alpha(if (bgPreview != null) 0.65f else 0.45f)
                    )
                    // Contrast overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (bgPreview != null) 0.35f else 0.25f))
                    )
                    // Crisp foreground icon/artwork
                    if (coilModel != null) {
                        AsyncImage(
                            model = coilModel,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(RPCSXColors.surfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.gamepad),
                            contentDescription = null,
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                if (isRunning) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                    ) {
                        InfoBadge(text = "RUNNING", color = RPCSXColors.errorColor)
                    }
                }
            }

            // Bottom title & tag bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isFocused) RPCSXColors.surfaceOverlay else RPCSXColors.surface
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    style = AppTypography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp
                    ),
                    color = if (isFocused) RPCSXColors.focusRing else RPCSXColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tag,
                        style = AppTypography.labelSmall.copy(
                            fontSize = 9.sp,
                            color = RPCSXColors.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun FirmwareCard(distance: Int, onClick: () -> Unit) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.85f else 0.65f

    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val glowIntensity by animateDpAsState(
        targetValue = if (isFocused) 20.dp else 0.dp,
        animationSpec = tween(200)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = onClick)
            .shadow(
                elevation = glowIntensity,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(RPCSXColors.surface, RPCSXColors.surfaceContainerHigh)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_cloud_download),
                        contentDescription = null,
                        tint = RPCSXColors.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "FIRMWARE REQUIRED",
                        style = AppTypography.labelSmall,
                        color = RPCSXColors.textSecondary
                    )
                }
            }
        }

        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)))
            )
        }
    }
}

@Composable
fun HintButton(text: String, icon: String, color: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (icon == "△") {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("▲", color = color, style = AppTypography.labelSmall.copy(fontSize = 14.sp))
            }
        } else if (icon == "■") {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(color, RoundedCornerShape(4.dp))
            )
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
fun AddGameCard(distance: Int, onClick: () -> Unit, disabled: Boolean = false) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.85f else 0.65f

    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val glowIntensity by animateDpAsState(
        targetValue = if (isFocused) 20.dp else 0.dp,
        animationSpec = tween(200)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = onClick)
            .shadow(
                elevation = glowIntensity,
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(RPCSXColors.surface, RPCSXColors.surfaceContainerHigh)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .border(2.dp, if (disabled) RPCSXColors.textDisabled else RPCSXColors.primary, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (disabled) "..." else "+", color = if (disabled) RPCSXColors.textDisabled else RPCSXColors.primary, style = AppTypography.headlineMedium)
                    }
                    Text(
                        if (disabled) "WAITING" else "ADD GAME",
                        style = AppTypography.labelSmall,
                        color = if (disabled) RPCSXColors.textDisabled else RPCSXColors.textSecondary
                    )
                }
            }
        }

        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)))
            )
        }
    }
}

@Composable
private fun TabletHomeScreen(
    pagerItems: List<PagerItem>,
    pagerState: androidx.compose.foundation.pager.PagerState,
    currentItem: PagerItem?,
    onPlayGame: (Game) -> Unit,
    onConfigureGame: (Game) -> Unit,
    onImportGame: () -> Unit,
    onInstallFirmware: () -> Unit,
    emulatorActiveGame: String?,
    gameplayRunning: Boolean,
    installPpu: CompileProgressBridge.CompileState,
    prelaunchPpu: CompileProgressBridge.CompileState,
    runtimePpu: CompileProgressBridge.CompileState,
    fullscreenAmbientModel: Any? = null,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Spotlight Header Area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (currentItem) {
                is PagerItem.GameItem -> {
                    val game = currentItem.game
                    val isRunning = gameplayRunning && emulatorActiveGame == game.info.path
                    val title = GameIdentity.displayName(game.info.path, game.info.name.value)
                    val titleId = GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
                        ?: game.info.path.substringAfterLast("/")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 30.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )

                            // Metadata Tags Row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                InfoBadge(text = titleId, color = RPCSXColors.primary)
                                val isIso = game.info.path.endsWith(".iso", ignoreCase = true) || game.info.path.startsWith("direct_iso")
                                InfoBadge(text = if (isIso) "PS3 ISO" else "INSTALLED")
                                if (isRunning) {
                                    InfoBadge(text = "RUNNING", color = RPCSXColors.errorColor)
                                }
                                val cardAvailability = com.zenithblue.sambas3.ppu.GameRunEligibilityHelper.evaluateAvailability(
                                    context, game, installPpu.ppuActive, prelaunchPpu, runtimePpu,
                                    RPCSX.state.value, RPCSX.activeGame.value
                                )
                                val isReady = cardAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready ||
                                    cardAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.GameplayRunning
                                InfoBadge(
                                    text = if (isReady) "READY TO PLAY" else "PREPARATION NEEDED",
                                    color = if (isReady) RPCSXColors.primary else Color(0xFFE5A93C)
                                )
                            }
                        }

                        // Action Buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { onPlayGame(game) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RPCSXColors.primary,
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.gamepad),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isRunning) "RESUME (X)" else "PLAY GAME (X)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }

                            OutlinedButton(
                                onClick = { onConfigureGame(game) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_settings),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "OPTIONS (▲)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
                is PagerItem.AddGame -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "ADD GAMES TO SAMBAS3",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 30.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = "Import PS3 disc ISO images or scanned game folders from your storage or SD card.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RPCSXColors.textSecondary
                            )
                        }

                        Button(
                            onClick = onImportGame,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RPCSXColors.primary,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("IMPORT GAME (ISO / FOLDER)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
                is PagerItem.FirmwareCard -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "PS3 SYSTEM FIRMWARE",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 30.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = "PlayStation 3 firmware (PS3UPDAT.PUP) is required to run games.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RPCSXColors.textSecondary
                            )
                        }

                        Button(
                            onClick = onInstallFirmware,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RPCSXColors.primary,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("INSTALL FIRMWARE (PUP)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
                else -> {}
            }
        }

        // Expanded Main Stage Cards Carousel (Large, Expansive & Centered)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val itemHeight = ((maxHeight - 32.dp) / 1.14f).coerceIn(240.dp, 440.dp)
            val itemWidth = itemHeight * 0.75f
            val horizontalPadding = if (maxWidth > itemWidth) (maxWidth - itemWidth) / 2 else 0.dp
            val verticalPadding = ((maxHeight - itemHeight) / 2).coerceAtLeast(16.dp)

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding
                ),
                pageSpacing = 36.dp,
                verticalAlignment = Alignment.CenterVertically,
                key = { idx -> pagerItems.getOrNull(idx)?.stableKey ?: "page:$idx" }
            ) { page ->
                val distance = abs(page - pagerState.currentPage)
                val item = pagerItems.getOrNull(page) ?: return@HorizontalPager
                when (item) {
                    is PagerItem.GameItem -> {
                        GameCard(
                            game = item.game,
                            distance = distance,
                            ambientBgModel = fullscreenAmbientModel,
                            onClick = {
                                if (distance == 0) {
                                    onPlayGame(item.game)
                                } else {
                                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                                }
                            },
                            onPlay = { onPlayGame(item.game) },
                            isRunning = gameplayRunning && emulatorActiveGame == item.game.info.path,
                            onConfigure = { onConfigureGame(item.game) }
                        )
                    }
                    is PagerItem.AddGame -> {
                        AddGameCard(
                            distance = distance,
                            onClick = if (item.disabled) ({}) else onImportGame,
                            disabled = item.disabled
                        )
                    }
                    is PagerItem.FirmwareCard -> {
                        FirmwareCard(
                            distance = distance,
                            onClick = onInstallFirmware
                        )
                    }
                    is PagerItem.SourceCandidate -> { }
                    is PagerItem.PendingImport -> {
                        PendingImportCard(
                            item = item,
                            distance = distance,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(page) } }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: Game,
    distance: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    isRunning: Boolean = false,
    ambientBgModel: Any? = null,
    onConfigure: () -> Unit = {}
) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.9f else 0.7f

    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val installProgressId = game.findProgress(GameProgressType.Install)?.firstOrNull()?.id
    val progressEntry = ProgressRepository.getItem(installProgressId)?.value
    val runtimeCompile by CompileProgressBridge.state.collectAsState()
    val installPpu by CompileProgressBridge.installState.collectAsState()
    val prelaunchPpu by CompileProgressBridge.prelaunchState.collectAsState()
    val isImporting = progressEntry != null
    val isRuntimeGameCompile = RPCSX.activeGame.value == game.info.path &&
        runtimeCompile.isActive
    val usingRuntimePpu = isRuntimeGameCompile && runtimeCompile.ppuActive
    val usingRuntimeShader = isRuntimeGameCompile && runtimeCompile.shaderActive && !usingRuntimePpu
    // Per-game PPU binding: prefer titleId match when available; fallback to placeholder progress for legacy/untagged installs.
    val gameKey = try { com.zenithblue.sambas3.GameIdentity.key(game.info.path, game.info.name.value) } catch (_: Exception) { "" }
    val installPpuTitle = installPpu.titleId?.uppercase()
    val prelaunchTitle = prelaunchPpu.titleId?.uppercase()
    val isPlaceholder = game.info.path == "$"
    val usingInstallPpu = installPpu.ppuActive && when {
        installPpuTitle != null -> !isPlaceholder && gameKey.equals(installPpuTitle, ignoreCase = true)
        else -> isImporting && !isPlaceholder || (isPlaceholder && gameKey == "path:$")
    }
    val usingPrelaunchPpu = prelaunchPpu.ppuActive && when {
        prelaunchTitle != null -> !isPlaceholder && gameKey.equals(prelaunchTitle, ignoreCase = true)
        else -> false
    }
    val showCompileOverlay = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.homeCardShowsCompileOverlay(
        isImporting = isImporting,
        isRuntimeGameCompile = isRuntimeGameCompile,
        usingPrelaunchPpu = usingPrelaunchPpu,
        usingInstallPpu = usingInstallPpu,
    )
    val context = LocalContext.current
    val readinessRevision by PpuReadinessStore.revision.collectAsState()
    val coordinatorRevision by com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.coordinatorRevision.collectAsState()
    @Suppress("UNUSED_VARIABLE")
    val cardPpuRev = readinessRevision + coordinatorRevision
    val cardTitleId = try {
        com.zenithblue.sambas3.GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
    } catch (_: Exception) { null }
    val cardAvailability = com.zenithblue.sambas3.ppu.GameRunEligibilityHelper.evaluateAvailability(
        context, game, installPpu.ppuActive, prelaunchPpu, runtimeCompile,
        RPCSX.state.value, RPCSX.activeGame.value
    )
    val cardPpuUi = com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.build(
        cardTitleId,
        cardAvailability,
        com.zenithblue.sambas3.ui.games.launch.LaunchRuntimeInputs(
            installPpu = installPpu,
            prelaunchPpu = prelaunchPpu,
            runtimePpu = runtimeCompile,
            emulatorState = RPCSX.state.value,
            activeGame = RPCSX.activeGame.value,
            waitingForIdle = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.waitingForIdle,
            deferredForFgs = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.deferredForFgs,
            fgsStartDenied = CompileProgressBridge.fgsStartDenied,
            preRuntimeState = cardTitleId?.let {
                runCatching { PpuReadinessStore.getPreRuntimeState(context, it) }
                    .getOrDefault(PreRuntimePpuState.NOT_DONE)
            } ?: PreRuntimePpuState.NOT_DONE,
            runtimeReadyState = cardTitleId?.let {
                runCatching { PpuReadinessStore.getRuntimeState(context, it) }
                    .getOrDefault(RuntimePpuState.NOT_STARTED)
            } ?: RuntimePpuState.NOT_STARTED,
            validatedByRealBootFrame = cardTitleId?.let {
                runCatching { PpuReadinessStore.isRuntimeValidated(context, it) }.getOrDefault(false)
            } ?: false,
            activeCompileTitleId = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.activeTitleId,
            stoppingCompile = com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.stopping,
        ),
    )
    val progressValue = when {
        usingRuntimePpu && runtimeCompile.moduleTotal > 0 -> runtimeCompile.moduleDone.toLong()
        usingInstallPpu && installPpu.moduleTotal > 0 -> installPpu.moduleDone.toLong()
        usingPrelaunchPpu && prelaunchPpu.moduleTotal > 0 -> prelaunchPpu.moduleDone.toLong()
        usingRuntimePpu -> runtimeCompile.ppuPercent.toLong()
        usingRuntimeShader -> runtimeCompile.shaderPercent.toLong()
        usingInstallPpu -> installPpu.ppuPercent.toLong()
        usingPrelaunchPpu -> prelaunchPpu.ppuPercent.toLong()
        else -> progressEntry?.value?.longValue ?: 0
    }
    val progressMax = when {
        usingRuntimePpu && runtimeCompile.moduleTotal > 0 -> runtimeCompile.moduleTotal.toLong()
        usingInstallPpu && installPpu.moduleTotal > 0 -> installPpu.moduleTotal.toLong()
        usingPrelaunchPpu && prelaunchPpu.moduleTotal > 0 -> prelaunchPpu.moduleTotal.toLong()
        usingRuntimePpu -> runtimeCompile.ppuMax.toLong()
        usingRuntimeShader -> 100L
        usingInstallPpu -> installPpu.ppuMax.toLong()
        usingPrelaunchPpu -> prelaunchPpu.ppuMax.toLong()
        else -> progressEntry?.max?.longValue ?: 0
    }
    val progressMessage = when {
        usingRuntimePpu -> com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(runtimeCompile)
        usingRuntimeShader -> runtimeCompile.shaderMsg ?: stringResource(R.string.compiling_shaders_desc)
        usingInstallPpu -> com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(installPpu)
        usingPrelaunchPpu -> com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.compileProgressLine(prelaunchPpu)
        else -> progressEntry?.message?.value
    }
    val isIndeterminate = when {
        usingRuntimeShader -> runtimeCompile.shaderPercent <= 0
        usingRuntimePpu || usingInstallPpu || usingPrelaunchPpu -> progressMax <= 0L
        else -> progressMax == 0L
    }
    val compileTitle = when {
        usingRuntimeShader -> stringResource(R.string.compiling_shaders_title)
        usingRuntimePpu || usingInstallPpu || usingPrelaunchPpu -> stringResource(R.string.compiling_ppu_title)
        else -> null
    }

    val colorMatrix = remember(isFocused) {
        if (isFocused) ColorMatrix() else ColorMatrix().apply { setToSaturation(0f) }
    }

    val glowIntensity by animateDpAsState(
        targetValue = if (isFocused) 20.dp else 0.dp,
        animationSpec = tween(200)
    )

    // Fill the pager page; the pager itself is already sized correctly for the aspect ratio
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .combinedClickable(
                onClick = { if (isFocused) onPlay() else onClick() },
                onLongClick = onConfigure
            )
            .shadow(
                elevation = glowIntensity,
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
        // Use compact progress layout when the card is short (landscape / wide screens)
        val isCompact = maxHeight < 200.dp

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = RPCSXColors.surface,
            modifier = Modifier.fillMaxSize()
        ) {
            val rawIconPath = game.info.iconPath.value
            val installedPreview = remember(rawIconPath) { GamePreviewRepository.resolveInstalledPreview(rawIconPath) }
            val coilModel: Any? = when (installedPreview) {
                is GamePreviewModel.LocalFile -> installedPreview.file
                is GamePreviewModel.ContentUri -> installedPreview.uri
                is GamePreviewModel.None -> null
            }
            val bgPreview by androidx.compose.runtime.produceState<Any?>(
                initialValue = null,
                key1 = game.info.path,
                key2 = rawIconPath
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when (val bg = GamePreviewRepository.resolveBackground(context, game)) {
                        is GamePreviewModel.LocalFile -> bg.file
                        is GamePreviewModel.ContentUri -> bg.uri
                        is GamePreviewModel.None -> null
                    }
                }
            }
            val cardBgModel = bgPreview ?: coilModel

            if (cardBgModel != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Blurred artwork background inside the game card
                    AsyncImage(
                        model = cardBgModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(colorMatrix),
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.15f)
                            .blur(radius = if (isFocused) 16.dp else 24.dp)
                            .alpha(if (bgPreview != null) 0.75f else 0.7f),
                        onError = { err ->
                            Log.e("GamePreview", "installed AsyncImage error title=${game.info.name.value} path=${game.info.path} raw=$rawIconPath model=$installedPreview exists=false len=-1 err=${err.result.throwable?.message}")
                        }
                    )

                    // Dark overlay for contrast
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Black.copy(alpha = 0.25f),
                                        Color.Black.copy(alpha = 0.55f),
                                    )
                                )
                            )
                    )

                    // Crisp foreground icon badge
                    if (coilModel != null) {
                        AsyncImage(
                            model = coilModel,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.colorMatrix(colorMatrix),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (isCompact) 8.dp else 16.dp),
                            onError = { err ->
                                Log.e("GamePreview", "installed AsyncImage error title=${game.info.name.value} path=${game.info.path} raw=$rawIconPath model=$installedPreview exists=false len=-1 err=${err.result.throwable?.message}")
                            }
                        )
                    }
                }
            } else if (rawIconPath != null) {
                Log.w("GamePreview", "installed preview None title=${game.info.name.value} path=${game.info.path} raw=$rawIconPath model=$installedPreview")
            }

            if (showCompileOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompact) {
                        // Horizontal compact layout for landscape/wide screens
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    color = RPCSXColors.primary,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                if (!isIndeterminate) {
                                    LinearProgressIndicator(
                                        progress = { (progressValue.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)),
                                        color = RPCSXColors.primary,
                                        trackColor = RPCSXColors.surfaceOverlay,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(4.dp)),
                                        color = RPCSXColors.primary,
                                        trackColor = RPCSXColors.surfaceOverlay,
                                    )
                                }
                            }
                            compileTitle?.let {
                                Text(
                                    text = it,
                                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                                    color = RPCSXColors.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = progressMessage ?: "Importing...",
                                style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                                color = RPCSXColors.textSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // Standard vertical layout
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
                                    progress = { (progressValue.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f) },
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
                            compileTitle?.let {
                                Text(
                                    text = it,
                                    style = AppTypography.labelMedium,
                                    color = RPCSXColors.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
        }

        if (!showCompileOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Install PPU  ${com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.phaseStatusLine(cardPpuUi.installPpu)}",
                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                    color = RPCSXColors.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "Runtime PPU  ${com.zenithblue.sambas3.ui.games.launch.LaunchPpuPresentation.phaseStatusLine(cardPpuUi.runtimePpu)}",
                    style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                    color = RPCSXColors.textSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        if (isFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)))
            )
        }

        if (isRunning) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        color = RPCSXColors.errorColor.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                    )
                    Text(
                        "RUNNING",
                        style = AppTypography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

fun bootGame(context: android.content.Context, game: Game, savestatePath: String? = null, savestateSlot: Int? = null) {
    if (game.hasFlag(GameFlag.Locked)) {
        return
    }
    val nativeState = runCatching { RPCSX.getState() }.getOrNull()
    if (nativeState != EmulatorState.Stopped) {
        Log.w("S3STOP", "boot blocked nativeState=${nativeState ?: "Unknown"} game=${game.info.path}")
        return
    }
    GameRepository.onBoot(game)
    val emulatorWindow = Intent(context, RPCSXActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    emulatorWindow.putExtra("path", game.info.path)
    emulatorWindow.putExtra(RPCSXActivity.EXTRA_ORIGINAL_GAME_PATH, game.info.path)
    if (savestatePath != null) {
        emulatorWindow.putExtra(RPCSXActivity.EXTRA_BOOT_MODE, EmulatorBootMode.UserSelectedSavestate.name)
        emulatorWindow.putExtra(RPCSXActivity.EXTRA_SAVESTATE_PATH, savestatePath)
        savestateSlot?.let { emulatorWindow.putExtra(RPCSXActivity.EXTRA_SAVESTATE_SLOT, it) }
    } else {
        emulatorWindow.putExtra(RPCSXActivity.EXTRA_BOOT_MODE, EmulatorBootMode.FreshGame.name)
    }
    context.startActivity(emulatorWindow)
}

@Composable
fun PendingImportCard(
    item: PagerItem.PendingImport,
    distance: Int,
    onClick: () -> Unit
) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.85f else 0.65f
    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))
    val glowIntensity by animateDpAsState(
        targetValue = if (isFocused) 20.dp else 0.dp,
        animationSpec = tween(200)
    )
    // Observe install PPU + generic install progress for same progressId
    val installPpu by CompileProgressBridge.installState.collectAsState()
    val progressEntry = ProgressRepository.getItem(item.progressId)?.value
    val isPpu = installPpu.ppuActive && (item.provisionalTitleId == null || installPpu.titleId?.equals(item.provisionalTitleId, ignoreCase = true) == true)
    val progressVal = if (isPpu) installPpu.ppuPercent.toLong() else progressEntry?.value?.longValue ?: 0L
    val progressMax = if (isPpu) installPpu.ppuMax.toLong() else progressEntry?.max?.longValue ?: 0L
    val msg = if (isPpu) installPpu.ppuMsg else progressEntry?.message?.value
    val title = if (isPpu) "COMPILING PPU" else "IMPORTING..."
    Box(
        modifier = Modifier.fillMaxSize().scale(scale).alpha(alpha).combinedClickable(onClick = onClick)
            .shadow(elevation = glowIntensity, spotColor = RPCSXColors.focusGlow, ambientColor = RPCSXColors.focusGlow, shape = RoundedCornerShape(8.dp))
            .border(width = if (isFocused) 2.dp else 1.dp, color = if (isFocused) RPCSXColors.focusRing else RPCSXColors.surfaceOverlay, shape = RoundedCornerShape(8.dp))
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = RPCSXColors.surface, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
                    Text(text = (item.displayName ?: "IMPORTING...").uppercase().take(28), style = AppTypography.headlineMedium.copy(letterSpacing = 1.sp), color = RPCSXColors.primary, textAlign = TextAlign.Center, maxLines = 2)
                    if (item.provisionalTitleId != null) InfoBadge(text = item.provisionalTitleId!!)
                    Text(title, style = AppTypography.labelSmall, color = RPCSXColors.primary)
                    if (progressMax > 0) {
                        LinearProgressIndicator(progress = { (progressVal.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = RPCSXColors.primary, trackColor = RPCSXColors.surfaceOverlay)
                    } else {
                        CircularProgressIndicator(color = RPCSXColors.primary, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    Text(msg ?: "Preparing...", style = AppTypography.labelSmall.copy(fontSize = 10.sp), color = RPCSXColors.textSecondary, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }
        if (isFocused) Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent))))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportMethodDialog(
    onDismiss: () -> Unit,
    onImportIso: () -> Unit
) {
    var focusedIndex by remember { mutableIntStateOf(0) } // 0 = ISO, 1 = Cancel
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    BackHandler {
        onDismiss()
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .width(480.dp)
            .padding(16.dp)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val code = keyEvent.nativeKeyEvent.keyCode
                    when {
                        keyEvent.key == Key.DirectionUp || code == KeyEvent.KEYCODE_DPAD_UP -> {
                            focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        keyEvent.key == Key.DirectionDown || code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                            focusedIndex = (focusedIndex + 1).coerceAtMost(1)
                            true
                        }
                        keyEvent.key == Key.DirectionCenter ||
                        keyEvent.key == Key.ButtonA ||
                        keyEvent.key == Key.Enter ||
                        code == KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == KeyEvent.KEYCODE_BUTTON_A ||
                        code == KeyEvent.KEYCODE_ENTER -> {
                            when (focusedIndex) {
                                0 -> onImportIso()
                                1 -> onDismiss()
                            }
                            true
                        }
                        keyEvent.key == Key.Back ||
                        keyEvent.key == Key.ButtonB ||
                        code == KeyEvent.KEYCODE_BACK ||
                        code == KeyEvent.KEYCODE_BUTTON_B -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = RPCSXColors.surfaceElevated,
            border = BorderStroke(1.5.dp, RPCSXColors.primary.copy(alpha = 0.6f)),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF161E38),
                                RPCSXColors.surfaceElevated,
                                RPCSXColors.surface
                            )
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: Title + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "IMPORT GAME",
                        style = AppTypography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 1.2.sp
                        ),
                        color = RPCSXColors.primary
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Close",
                            tint = RPCSXColors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Fine gold gradient divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    RPCSXColors.primary.copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                ImportOptionTile(
                    title = "ISO FILE",
                    formatTag = "DISC IMAGE",
                    iconRes = R.drawable.hard_drive,
                    isFocused = focusedIndex == 0,
                    onClick = onImportIso
                )

                // Footer: Controller hints + Ghost Cancel button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Controller hints
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = RPCSXColors.primary.copy(alpha = 0.22f),
                                border = BorderStroke(1.dp, RPCSXColors.primary)
                            ) {
                                Text(
                                    text = "✕",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = RPCSXColors.primary
                                )
                            }
                            Text(
                                text = "SELECT",
                                style = AppTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = RPCSXColors.textSecondary
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = RPCSXColors.textSecondary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, RPCSXColors.textSecondary)
                            ) {
                                Text(
                                    text = "○",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = AppTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = RPCSXColors.textSecondary
                                )
                            }
                            Text(
                                text = "CANCEL",
                                style = AppTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                color = RPCSXColors.textSecondary
                            )
                        }
                    }

                    // Cancel Ghost Button per design_3.md
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (focusedIndex == 1) RPCSXColors.primaryMuted else Color.Transparent,
                            contentColor = if (focusedIndex == 1) RPCSXColors.primary else RPCSXColors.textSecondary
                        ),
                        border = if (focusedIndex == 1) BorderStroke(1.dp, RPCSXColors.focusRing) else null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "CANCEL",
                            style = AppTypography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportOptionTile(
    title: String,
    formatTag: String,
    iconRes: Int,
    isFocused: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isFocused) RPCSXColors.focusRing else RPCSXColors.surfaceOverlay
    val borderWidth = if (isFocused) 2.dp else 1.dp
    val cardBg = if (isFocused) {
        Brush.horizontalGradient(
            listOf(
                RPCSXColors.surfaceOverlay,
                Color(0xFF1F2B52)
            )
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                RPCSXColors.surface,
                Color(0xFF11172A)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        if (isFocused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(8.dp)
                    .background(
                        color = RPCSXColors.focusGlow.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(8.dp))
                .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (isFocused) RPCSXColors.primary.copy(alpha = 0.22f) else RPCSXColors.surfaceElevated,
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isFocused) RPCSXColors.primary else RPCSXColors.surfaceOverlay
                        ),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (isFocused) RPCSXColors.primary else RPCSXColors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = title,
                style = AppTypography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = if (isFocused) RPCSXColors.focusRing else RPCSXColors.textPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Surface(
                shape = RoundedCornerShape(3.dp),
                color = if (isFocused) RPCSXColors.primaryMuted else RPCSXColors.surfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (isFocused) RPCSXColors.primary.copy(alpha = 0.6f) else RPCSXColors.surfaceOverlay
                )
            ) {
                Text(
                    text = formatTag,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = AppTypography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isFocused) RPCSXColors.primary else RPCSXColors.textSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_keyboard_arrow_right),
                contentDescription = null,
                tint = if (isFocused) RPCSXColors.focusRing else RPCSXColors.textDisabled,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
