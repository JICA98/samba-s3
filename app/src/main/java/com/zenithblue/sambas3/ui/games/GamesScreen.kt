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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.zenithblue.sambas3.ui.games.launch.StoppedTrophiesDialog
import com.zenithblue.sambas3.ui.ingame.TrophiesData
import com.zenithblue.sambas3.ui.achievements.AchievementEvents
import com.zenithblue.sambas3.ui.achievements.AchievementRepository
import com.zenithblue.sambas3.crash.HomeRecoveryRepository
import com.zenithblue.sambas3.crash.HomeRecoveryState
import com.zenithblue.sambas3.crash.RecoveryAction
import com.zenithblue.sambas3.ui.crash.CrashDetailsSheet
import com.zenithblue.sambas3.ui.crash.CrashRecoveryCard
import com.zenithblue.sambas3.ui.crash.StopFailureCard
import com.zenithblue.sambas3.session.EmulatorStopCoordinator
import com.zenithblue.sambas3.utils.FileUtil
import com.zenithblue.sambas3.utils.GameFolderMatch
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
    data class AddGame(val disabled: Boolean = false) : PagerItem() {
        override val stableKey: String get() = if (disabled) "add:disabled" else "add"
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
    showBothEnds: Boolean
): List<PagerItem> = buildList {
    val hasLibrary = visibleGames.isNotEmpty() || sourceCandidates.isNotEmpty() || pendingImports.isNotEmpty()
    if (!hasLibrary) {
        if (!hasFw) add(PagerItem.FirmwareCard)
        else if (isFwInstalling) add(PagerItem.AddGame(disabled = true))
        else add(PagerItem.AddGame())
    } else {
        if (showBothEnds) {
            add(PagerItem.AddGame())
            addAll(visibleGames.map { PagerItem.GameItem(it) })
            addAll(pendingImports)
            addAll(sourceCandidates)
            add(PagerItem.AddGame())
        } else {
            addAll(visibleGames.map { PagerItem.GameItem(it) })
            addAll(pendingImports)
            addAll(sourceCandidates)
            add(PagerItem.AddGame())
        }
    }
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
    navigateToLogs: (() -> Unit)? = null,
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
    LaunchedEffect(Unit) { HomeRecoveryRepository.refresh(context) }

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

    var focusedIndex by remember { mutableStateOf(if (games.isNotEmpty()) 0 else -1) }
    var bootingGame by remember { mutableStateOf<Game?>(null) }
    var launchCenterGame by remember { mutableStateOf<Game?>(null) }
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
        stoppedTrophies = titleId?.let { AchievementRepository.title(it, force = true) }
        stoppedTrophiesLoading = false
    }
    val recoveryScope = rememberCoroutineScope()
    var showImportDialog by remember { mutableStateOf(false) }
    var scannedFolderGames by remember { mutableStateOf<List<GameFolderMatch>?>(null) }
    var scannedFolderUri by remember { mutableStateOf<Uri?>(null) }
    var scanningFolder by remember { mutableStateOf(false) }
    var configureGameTarget by remember { mutableStateOf<Game?>(null) }
    var configuringGame by remember { mutableStateOf(false) }
    var removeGameTarget by remember { mutableStateOf<Game?>(null) }
    var removingGame by remember { mutableStateOf(false) }
    var removeGameFailed by remember { mutableStateOf(false) }
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
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            PrecompilerService.start(context, PrecompilerServiceAction.Install, uri)
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
            scannedFolderUri = uri
            scannedFolderGames = null
            scanningFolder = true
            thread(name = "sambas3-folder-scan") {
                val matches = FileUtil.scanGameFolder(context, uri)
                context.mainExecutor.execute {
                    scannedFolderGames = matches
                    scanningFolder = false
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
    val activeInstallId by GameRepository.activeInstallProgress
    val activeInstallEntry = ProgressRepository.getItem(activeInstallId)?.value
    val isPackageInstalling = activeInstallId != null

    // BLOCKER C: observe persisted ISO candidates as StateFlow (JSON) and merge with Home
    val candidateList by com.zenithblue.sambas3.utils.LibraryCandidatesRepository.candidatesFlow.collectAsState()
    LaunchedEffect(Unit) { com.zenithblue.sambas3.utils.LibraryCandidatesRepository.refresh(context) }

    // BLOCKER D: observe pending import sessions — one stable card per import
    val importSessions by com.zenithblue.sambas3.ImportSessionStore.sessions.collectAsState()

    // BLOCKER B fix: do not memoize mutable SnapshotStateList with remember(games) or remember(size).
    // Derive directly during composition so placeholder add/remove/replace is observed.
    // Hide legacy "$" placeholder entirely — pending UI is now ImportSession/PendingImport, not a fake Game.
    val visibleGames: List<Game> = games.filterNot { it.info.path == "$" }
    // Merge source ISO candidates (folder scan) — installed wins over duplicate titleId
    val installedTitleIds = visibleGames.mapNotNull { com.zenithblue.sambas3.GameIdentity.titleIdOrNull(it.info.path, it.info.name.value) }.map { it.uppercase() }.toSet()
    // Dedupe: hide source candidate if same titleId already installed or currently importing (pending)
    val pendingTitleIds = (importSessions.mapNotNull { it.provisionalTitleId?.uppercase() } + importSessions.mapNotNull { it.resolvedTitleId?.uppercase() }).toSet()
    val allInstalledOrPendingIds = installedTitleIds + pendingTitleIds
    val sourceCandidateItems: List<PagerItem.SourceCandidate> = candidateList.mapNotNull { cand ->
        val tid = cand.titleId?.uppercase()
        if (tid != null && tid in allInstalledOrPendingIds) return@mapNotNull null
        PagerItem.SourceCandidate(cand.titleId, cand.folderName, cand.sourceUri?.toString() ?: cand.folderName, cand.sourceKind)
    }
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
    val showBothEnds = (visibleGames.size + sourceCandidateItems.size + pendingItems.size) > 5
    val pagerItems: List<PagerItem> = buildLibraryPagerItems(visibleGames, sourceCandidateItems, pendingItems, hasFw, isFwInstalling, showBothEnds)
    val initialPage = if (showBothEnds) 1 else 0
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pagerItems.size })
    // Clamp pager when list shrinks (removal crash safety — F1)
    LaunchedEffect(pagerItems.size) {
        if (pagerItems.isNotEmpty()) {
            val target = pagerState.currentPage.coerceAtMost(pagerItems.lastIndex)
            if (target != pagerState.currentPage) {
                try { pagerState.scrollToPage(target) } catch (_: Exception) {}
            }
        }
    }
    val currentItem = pagerItems.getOrNull(pagerState.currentPage)
    val selectedIconPath = (currentItem as? PagerItem.GameItem)?.game?.info?.iconPath?.value
    val selectedPreview = remember(selectedIconPath) { GamePreviewRepository.resolveInstalledPreview(selectedIconPath) }
    val selectedCoilModel: Any? = when (selectedPreview) {
        is GamePreviewModel.LocalFile -> selectedPreview.file
        is GamePreviewModel.ContentUri -> selectedPreview.uri
        is GamePreviewModel.None -> null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RPCSXColors.background)
    ) {
        // Frosted enlarged cover of focused game
        if (selectedCoilModel != null) {
            AsyncImage(
                model = selectedCoilModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.45f)
                    .blur(radius = 36.dp)
                    .alpha(0.55f),
                onError = { err ->
                    val title = (currentItem as? PagerItem.GameItem)?.game?.info?.name?.value
                    val path = (currentItem as? PagerItem.GameItem)?.game?.info?.path
                    Log.w("GamePreview", "frosted preview error title=$title path=$path err=${err.result.throwable?.message}")
                }
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        // Vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
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
        )

        Column(modifier = Modifier.fillMaxSize()) {
            if (recoveryState !is HomeRecoveryState.None) {
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
                    onViewLogs = { navigateToLogs?.invoke() },
                    onDismiss = { HomeRecoveryRepository.dismiss(context) },
                )
            }
            (stopState as? com.zenithblue.sambas3.session.EmulatorStopState.Failed)?.let { failedStop ->
                StopFailureCard(
                    state = failedStop,
                    onRecheck = {
                        recoveryScope.launch {
                            EmulatorStopCoordinator.stop(context, failedStop.reason)
                        }
                    },
                    onViewLogs = { navigateToLogs?.invoke() },
                    onForceClose = {
                        Log.e("S3STOP", "user requested force-close requestId=${failedStop.requestId}")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            }
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
                    Image(
                        painter = painterResource(R.mipmap.ic_sambas3_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                    Text("SambaS3", style = AppTypography.displayLarge.copy(letterSpacing = 4.sp), color = RPCSXColors.primary)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
                        style = AppTypography.labelMedium,
                        color = RPCSXColors.textSecondary
                    )
                    IconButton(onClick = { navigateToSettings?.invoke() }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = RPCSXColors.primary)
                    }
                }
            }

            if (visibleGames.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.no_games_yet),
                            style = AppTypography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = RPCSXColors.textSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { showImportDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = RPCSXColors.primary,
                                    contentColor = RPCSXColors.background,
                                ),
                            ) {
                                Text(stringResource(R.string.import_game_action))
                            }
                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = RPCSXColors.primary,
                                ),
                            ) {
                                Text(stringResource(R.string.game_folder_scan_action))
                            }
                        }
                    }
                }
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // On wide/landscape screens maxHeight is small, so drive size from the
                    // smaller of width and height to keep cards a reasonable, readable size.
                    val isLandscape = maxWidth > maxHeight
                    val itemSize = if (isLandscape) {
                        // In landscape: constrain by height but allow more room
                        val h = (maxHeight - 32.dp).coerceAtLeast(120.dp)
                        h
                    } else {
                        (maxHeight - 64.dp) * 0.8f
                    }
                    val itemHeight = itemSize
                    val itemWidth = itemHeight * (if (isLandscape) 0.75f else 0.85f)
                    val horizontalPadding = if (maxWidth > itemWidth) (maxWidth - itemWidth) / 2 else 0.dp
                    val coroutineScope = rememberCoroutineScope()

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().scale(bootScale),
                        contentPadding = PaddingValues(
                            horizontal = horizontalPadding,
                            vertical = if (isLandscape) 8.dp else 32.dp
                        ),
                        pageSpacing = 16.dp,
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
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(page) } },
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
                            is PagerItem.SourceCandidate -> {
                                SourceCandidateCard(
                                    item = item,
                                    distance = distance,
                                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(page) } },
                                    onImport = {
                                        val uri = try { android.net.Uri.parse(item.sourceUri) } catch (_: Exception) { null }
                                        if (uri != null) {
                                            try {
                                                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            } catch (_: Exception) {}
                                            PrecompilerService.start(context, PrecompilerServiceAction.Install, uri)
                                        }
                                    }
                                )
                            }
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

                // Focused Game Info
                if (currentItem is PagerItem.GameItem) {
                    val activeGame = currentItem.game
                    val isActiveGameRunning = isRunning && emulatorActiveGame.value == activeGame.info.path
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(bootAlpha)
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when {
                                activeGame.info.path == "$" -> "IMPORTING..."
                                else -> (activeGame.info.name.value ?: "UNKNOWN GAME").uppercase()
                            },
                            style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp),
                            color = RPCSXColors.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                            if (isActiveGameRunning) {
                                InfoBadge(text = "RUNNING", color = RPCSXColors.errorColor)
                            }
                            InfoBadge(text = activeGame.info.path.substringAfterLast("/"))
                        }
                    }
                } else if (currentItem is PagerItem.AddGame) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(bootAlpha)
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (currentItem.disabled) "WAITING FOR FIRMWARE..." else "ADD GAME",
                            style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp),
                            color = if (currentItem.disabled) RPCSXColors.textDisabled else RPCSXColors.textSecondary
                        )
                    }
                } else if (currentItem is PagerItem.FirmwareCard) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(bootAlpha)
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "INSTALL FIRMWARE",
                            style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp),
                            color = RPCSXColors.textSecondary
                        )
                    }
                } else if (currentItem is PagerItem.SourceCandidate) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(bootAlpha)
                            .padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentItem.displayName.uppercase(),
                            style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp),
                            color = RPCSXColors.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            InfoBadge(text = "ISO", color = RPCSXColors.textSecondary)
                            InfoBadge(text = "Not installed")
                            if (currentItem.titleId != null) InfoBadge(text = currentItem.titleId!!)
                        }
                        Text(
                            text = "Pre-runtime PPU: Not done  •  Runtime PPU: Not started",
                            style = AppTypography.labelSmall,
                            color = RPCSXColors.textSecondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else if (currentItem is PagerItem.PendingImport) {
                    Column(
                        modifier = Modifier.fillMaxWidth().alpha(bootAlpha).padding(top = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = (currentItem.displayName ?: "IMPORTING...").uppercase(), style = AppTypography.headlineMedium.copy(letterSpacing = 2.sp), color = RPCSXColors.primary)
                        if (currentItem.provisionalTitleId != null) {
                            Box(modifier = Modifier.padding(top = 4.dp)) {
                                InfoBadge(text = currentItem.provisionalTitleId!!)
                            }
                        }
                        Text(text = "Import in progress — same card will show PPU", style = AppTypography.labelSmall, color = RPCSXColors.textSecondary, modifier = Modifier.padding(top = 4.dp))
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
                    .padding(horizontal = 16.dp, vertical = 2.dp),
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
                        LinearProgressIndicator(
                            progress = { (installPpu.ppuPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .widthIn(min = 80.dp, max = 200.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = RPCSXColors.primary,
                            trackColor = RPCSXColors.surfaceOverlay,
                        )
                        installPpu.ppuMsg?.let {
                            Text(
                                text = it,
                                style = AppTypography.labelSmall,
                                color = RPCSXColors.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
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
                        prelaunchPpu.ppuMsg?.let {
                            Text(
                                text = it,
                                style = AppTypography.labelSmall,
                                color = RPCSXColors.textSecondary,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
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
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
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
                        val hintGame = (currentItem as? PagerItem.GameItem)?.game
                        val hintAvailability = hintGame?.let {
                            com.zenithblue.sambas3.ppu.GameRunEligibilityHelper.evaluateAvailability(
                                context, it, installPpu.ppuActive, prelaunchPpu, runtimePpu, emulatorState.value, emulatorActiveGame.value
                            )
                        }
                        when (hintAvailability) {
                            is com.zenithblue.sambas3.ppu.GameLaunchAvailability.PreparingPpu, is com.zenithblue.sambas3.ppu.GameLaunchAvailability.WaitingForEngineIdle, is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Importing, is com.zenithblue.sambas3.ppu.GameLaunchAvailability.EngineBusy -> {
                                HintButton(text = "PREPARING", icon = "X", color = RPCSXColors.textDisabled, onClick = { })
                            }
                            is com.zenithblue.sambas3.ppu.GameLaunchAvailability.NeedsPreparation -> {
                                HintButton(text = "PREPARE", icon = "X", color = RPCSXColors.primary, onClick = {
                                    hintGame?.let { com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, it) }
                                })
                            }
                            is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Failed -> {
                                HintButton(text = "RETRY", icon = "X", color = RPCSXColors.errorColor, onClick = {
                                    hintGame?.let { com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, it) }
                                })
                            }
                            is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready -> {
                                HintButton(text = "PLAY", icon = "X", color = RPCSXColors.primary, onClick = { launchCenterGame = hintGame })
                            }
                            else -> {
                                // No game or import required -> still show PLAY disabled or ADD?
                                val isPlayable = hintAvailability == null || hintAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready
                                HintButton(
                                    text = "PLAY",
                                    icon = "X",
                                    color = if (isPlayable || hintGame == null) RPCSXColors.primary else RPCSXColors.textDisabled,
                                    onClick = {
                                        if (hintGame != null && hintAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.Ready) launchCenterGame = hintGame
                                        else if (hintAvailability is com.zenithblue.sambas3.ppu.GameLaunchAvailability.NeedsPreparation) {
                                            com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.requestPreparation(context, hintGame!!)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    HintButton(text = "OPTIONS", icon = "△", color = RPCSXColors.textSecondary, onClick = { navigateToSettings?.invoke() })
                }
            }
        }

        launchCenterGame?.let { game ->
            GameLaunchCenter(
                snapshot = GameLaunchRepository.snapshot(context, game),
                onDismiss = { launchCenterGame = null },
                onFreshPlay = {
                    launchCenterGame = null
                    bootingGame = game
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
                    launchCenterGame = null
                    configureGameTarget = game
                },
                onDriver = {
                    launchCenterGame = null
                    (navigateToDrivers ?: navigateToSettings)?.invoke()
                },
                onPatches = {
                    launchCenterGame = null
                    (navigateToPatches ?: navigateToSettings)?.invoke()
                },
                onAchievements = {
                    stoppedTrophiesLoading = true
                    stoppedTrophies = null
                }
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
                onViewLogs = { navigateToLogs?.invoke() },
                onDismiss = { detailsState = null },
            )
        }

        if (stoppedTrophiesLoading || stoppedTrophies != null) {
            StoppedTrophiesDialog(
                data = stoppedTrophies,
                loading = stoppedTrophiesLoading,
                onDismiss = {
                    stoppedTrophiesLoading = false
                    stoppedTrophies = null
                }
            )
        }

        if (configureGameTarget != null) {
            // Engine gate (review F7): reading/editing the config tree requires a
            // live initialized engine that is NOT running a game.
            val engineIdle = RPCSX.activeLibrary.value != null &&
                runCatching { RPCSX.getState() == EmulatorState.Stopped }.getOrDefault(false)
            ModalBottomSheet(
                onDismissRequest = {
                    configureGameTarget = null
                    configuringGame = false
                }
            ) {
                if (!configuringGame) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(enabled = engineIdle) { configuringGame = true }
                                .padding(vertical = 12.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.tune),
                                contentDescription = null,
                                tint = if (engineIdle) RPCSXColors.primary else RPCSXColors.textDisabled,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.configure_game),
                                    style = AppTypography.bodyLarge,
                                    color = if (engineIdle) RPCSXColors.textPrimary else RPCSXColors.textDisabled
                                )
                                if (!engineIdle) {
                                    Text(
                                        text = stringResource(R.string.configure_game_gate_description),
                                        style = AppTypography.labelSmall,
                                        color = RPCSXColors.textSecondary
                                    )
                                } else {
                                    Text(
                                        text = (configureGameTarget?.info?.name?.value
                                            ?: configureGameTarget?.info?.path?.substringAfterLast('/')
                                            ?: "").uppercase(),
                                        style = AppTypography.labelSmall,
                                        color = RPCSXColors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                } else {
                    GameConfigureScreen(
                        gamePath = configureGameTarget?.info?.path,
                        modifier = Modifier.heightIn(max = 640.dp),
                        onClose = {
                            configuringGame = false
                            configureGameTarget = null
                        },
                        onRemove = {
                            removeGameTarget = configureGameTarget
                            configuringGame = false
                            configureGameTarget = null
                        },
                    )
                }
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

        if (scanningFolder) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.game_folder_scan_title)) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = RPCSXColors.primary,
                        )
                        Text(stringResource(R.string.game_folder_scanning))
                    }
                },
                confirmButton = {},
            )
        }

        scannedFolderGames?.let { matches ->
            GameFolderScanDialog(
                matches = matches,
                onDismiss = {
                    scannedFolderGames = null
                    scannedFolderUri = null
                },
                onImport = {
                    scannedFolderUri?.let { FileUtil.installPackages(context, it) }
                    scannedFolderGames = null
                    scannedFolderUri = null
                },
            )
        }
    }
}

@Composable
private fun GameFolderScanDialog(
    matches: List<GameFolderMatch>,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.game_folder_scan_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (matches.isEmpty()) {
                    Text(stringResource(R.string.game_folder_no_games))
                } else {
                    Text(stringResource(R.string.game_folder_found_count, matches.size))
                    matches.forEach { match ->
                        Surface(
                            color = RPCSXColors.surfaceElevated,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = match.folderName,
                                    style = AppTypography.bodyLarge,
                                    color = RPCSXColors.textPrimary,
                                )
                                match.titleId?.let {
                                    Text(
                                        text = it,
                                        style = AppTypography.labelMedium,
                                        color = RPCSXColors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = matches.isNotEmpty()) {
                Text(stringResource(R.string.game_folder_import_found))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
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

@Composable
fun FirmwareCard(distance: Int, onClick: () -> Unit) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f

    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = onClick)
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
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f

    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))

    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .clickable(onClick = onClick)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    game: Game,
    distance: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    isRunning: Boolean = false,
    onConfigure: () -> Unit = {}
) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f

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
    val showCompileOverlay = isImporting || isRuntimeGameCompile || usingPrelaunchPpu
    val progressValue = when {
        usingRuntimePpu -> runtimeCompile.ppuPercent.toLong()
        usingInstallPpu -> installPpu.ppuPercent.toLong()
        usingPrelaunchPpu -> prelaunchPpu.ppuPercent.toLong()
        else -> progressEntry?.value?.longValue ?: 0
    }
    val progressMax = when {
        usingRuntimePpu -> runtimeCompile.ppuMax.toLong()
        usingInstallPpu -> installPpu.ppuMax.toLong()
        usingPrelaunchPpu -> prelaunchPpu.ppuMax.toLong()
        else -> progressEntry?.max?.longValue ?: 0
    }
    val progressMessage = when {
        usingRuntimePpu -> runtimeCompile.ppuMsg ?: stringResource(R.string.compiling_ppu_title)
        usingRuntimeShader -> runtimeCompile.shaderMsg ?: stringResource(R.string.compiling_shaders_desc)
        usingInstallPpu -> installPpu.ppuMsg ?: stringResource(R.string.compiling_ppu_title)
        usingPrelaunchPpu -> prelaunchPpu.ppuMsg ?: "Preparing PPU"
        else -> progressEntry?.message?.value
    }
    val isIndeterminate = when {
        usingRuntimeShader -> true
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

    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
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
            if (coilModel != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Blurred ambient background
                    AsyncImage(
                        model = coilModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = ColorFilter.colorMatrix(colorMatrix),
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1.3f)
                            .blur(radius = 16.dp)
                            .alpha(0.5f),
                        onError = { err ->
                            val exists = if (installedPreview is GamePreviewModel.LocalFile) installedPreview.file.exists() else false
                            val len = if (installedPreview is GamePreviewModel.LocalFile && exists) installedPreview.file.length() else -1L
                            Log.e("GamePreview", "installed AsyncImage error title=${game.info.name.value} path=${game.info.path} raw=$rawIconPath model=$installedPreview exists=$exists len=$len err=${err.result.throwable?.message}")
                        }
                    )

                    // Dark overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f))
                    )

                    // Crisp foreground image
                    AsyncImage(
                        model = coilModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.colorMatrix(colorMatrix),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isCompact) 8.dp else 16.dp),
                        onError = { err ->
                            val exists = if (installedPreview is GamePreviewModel.LocalFile) installedPreview.file.exists() else false
                            val len = if (installedPreview is GamePreviewModel.LocalFile && exists) installedPreview.file.length() else -1L
                            Log.e("GamePreview", "installed AsyncImage error title=${game.info.name.value} path=${game.info.path} raw=$rawIconPath model=$installedPreview exists=$exists len=$len err=${err.result.throwable?.message}")
                        }
                    )
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
                                maxLines = 1,
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
    val emulatorWindow = Intent(context, RPCSXActivity::class.java)
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
fun SourceCandidateCard(
    item: PagerItem.SourceCandidate,
    distance: Int,
    onClick: () -> Unit,
    onImport: () -> Unit
) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f
    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))
    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f,
        targetValue = 35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val context = LocalContext.current
    var preview by remember(item.sourceUri) { mutableStateOf<GamePreviewModel>(GamePreviewModel.None) }
    LaunchedEffect(item.sourceUri, item.sourceKind) {
        try {
            val uri = try { Uri.parse(item.sourceUri) } catch (_: Exception) { null }
            if (uri != null) {
                val kind = item.sourceKind ?: if (item.sourceUri.endsWith(".iso", ignoreCase = true) || item.displayName.endsWith(".iso", ignoreCase = true)) com.zenithblue.sambas3.utils.GameSourceKind.ISO else com.zenithblue.sambas3.utils.GameSourceKind.DIRECTORY
                val result = GamePreviewRepository.resolvePreview(context, uri, kind)
                preview = result
                if (result is GamePreviewModel.None) {
                    Log.d("GamePreview", "candidate preview None for ${item.displayName} uri=${item.sourceUri} kind=$kind")
                }
            }
        } catch (e: Exception) {
            Log.w("GamePreview", "candidate preview failed ${item.displayName}: ${e.message}")
            preview = GamePreviewModel.None
        }
    }
    val coilModel: Any? = when (preview) {
        is GamePreviewModel.LocalFile -> (preview as GamePreviewModel.LocalFile).file
        is GamePreviewModel.ContentUri -> (preview as GamePreviewModel.ContentUri).uri
        is GamePreviewModel.None -> null
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .scale(scale)
            .alpha(alpha)
            .combinedClickable(onClick = { if (isFocused) onImport() else onClick() })
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
        Surface(shape = RoundedCornerShape(8.dp), color = RPCSXColors.surface, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Preview artwork — blurred background + crisp foreground, fallback to text-only if None
                if (coilModel != null) {
                    AsyncImage(
                        model = coilModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(radius = 16.dp).alpha(0.55f),
                        onError = { err ->
                            Log.w("GamePreview", "candidate preview load failed ${item.displayName} uri=${item.sourceUri} err=${err.result.throwable?.message}")
                        }
                    )
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    AsyncImage(
                        model = coilModel,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        onError = { err ->
                            Log.w("GamePreview", "candidate preview load failed fg ${item.displayName} err=${err.result.throwable?.message}")
                        }
                    )
                    // Dark scrim for text readability
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = item.displayName.uppercase().take(28),
                        style = AppTypography.headlineMedium.copy(letterSpacing = 1.sp),
                        color = RPCSXColors.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InfoBadge(text = "ISO", color = RPCSXColors.textSecondary)
                        if (item.titleId != null) InfoBadge(text = item.titleId!!)
                    }
                    Text("Not installed", style = AppTypography.labelSmall, color = RPCSXColors.textSecondary)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Pre-runtime PPU: Not done", style = AppTypography.labelSmall.copy(fontSize = 10.sp), color = RPCSXColors.textSecondary)
                        Text("Runtime PPU: Not started", style = AppTypography.labelSmall.copy(fontSize = 10.sp), color = RPCSXColors.textSecondary)
                    }
                    if (isFocused) {
                        Button(
                            onClick = onImport,
                            colors = ButtonDefaults.buttonColors(containerColor = RPCSXColors.primary, contentColor = RPCSXColors.background),
                            shape = RoundedCornerShape(4.dp)
                        ) { Text("IMPORT", style = AppTypography.labelSmall) }
                    }
                }
            }
        }
        if (isFocused) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent))))
        }
    }
}

@Composable
fun PendingImportCard(
    item: PagerItem.PendingImport,
    distance: Int,
    onClick: () -> Unit
) {
    val isFocused = distance == 0
    val targetScale = if (isFocused) 1.12f else if (distance == 1) 0.95f else 0.85f
    val targetAlpha = if (isFocused) 1.0f else if (distance == 1) 0.6f else 0.4f
    val scale by animateFloatAsState(targetScale, animationSpec = tween(300))
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(300))
    val infiniteTransition = rememberInfiniteTransition()
    val glowIntensity by infiniteTransition.animateFloat(
        initialValue = 15f, targetValue = 35f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse)
    )
    // Observe install PPU + generic install progress for same progressId
    val installPpu by CompileProgressBridge.installState.collectAsState()
    val progressEntry = ProgressRepository.getItem(item.progressId)?.value
    val isPpu = installPpu.ppuActive && (item.provisionalTitleId == null || installPpu.titleId?.equals(item.provisionalTitleId, ignoreCase = true) == true)
    val progressVal = if (isPpu) installPpu.ppuPercent.toLong() else progressEntry?.value?.longValue ?: 0L
    val progressMax = if (isPpu) installPpu.ppuMax.toLong() else progressEntry?.max?.longValue ?: 0L
    val msg = if (isPpu) installPpu.ppuMsg else progressEntry?.message?.value
    val title = if (isPpu) "COMPILING PPU" else "IMPORTING..."
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().scale(scale).alpha(alpha).combinedClickable(onClick = onClick)
            .shadow(elevation = if (isFocused) glowIntensity.dp else 0.dp, spotColor = RPCSXColors.focusGlow, ambientColor = RPCSXColors.focusGlow, shape = RoundedCornerShape(8.dp))
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
