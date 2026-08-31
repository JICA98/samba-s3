package com.zenithblue.sambas3

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenithblue.sambas3.databinding.ActivityRpcs3Binding
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.overlay.State
import com.zenithblue.sambas3.ui.ingame.CloseReason
import com.zenithblue.sambas3.ui.ingame.GameplayInputGate
import com.zenithblue.sambas3.ui.ingame.InGameMenuCoordinator
import com.zenithblue.sambas3.ui.ingame.InGameMenuCoreGateway
import com.zenithblue.sambas3.ui.ingame.InGameMenuHost
import com.zenithblue.sambas3.ui.ingame.InGameMenuIntent
import com.zenithblue.sambas3.ui.ingame.InGameMenuInputRouter
import com.zenithblue.sambas3.ui.ingame.MenuCommand
import com.zenithblue.sambas3.ui.ingame.MenuSessionState
import com.zenithblue.sambas3.ui.ingame.PhysicalInputTracker
import com.zenithblue.sambas3.ui.ingame.RpcsxInGameMenuCoreGateway
import com.zenithblue.sambas3.ui.ingame.RpcsxBridgeAdapter
import com.zenithblue.sambas3.ui.ingame.TrophyEvents
import com.zenithblue.sambas3.ui.emulation.EmulatorInteractionLock
import com.zenithblue.sambas3.ui.emulation.InteractionLock
import com.zenithblue.sambas3.ui.emulation.SavestateOperationUiState
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.session.EmulationSessionJournal
import com.zenithblue.sambas3.session.EmulationSessionState
import com.zenithblue.sambas3.session.SessionStatePairing
import com.zenithblue.sambas3.session.SessionStateReconciliation
import com.zenithblue.sambas3.session.CoreRecoveryCoordinator
import com.zenithblue.sambas3.session.StopResult
import com.zenithblue.sambas3.crash.CrashEvidenceCollector
import com.zenithblue.sambas3.crash.CrashReport
import com.zenithblue.sambas3.ui.crash.GameCrashScreen
import com.zenithblue.sambas3.ui.games.launch.GameSavestateRepository
import com.zenithblue.sambas3.monitoring.MonitoringOverlaySettings
import com.zenithblue.sambas3.monitoring.MonitoringRepository
import com.zenithblue.sambas3.ui.monitoring.MonitoringOverlay
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.input.ControllerProfileRepository
import com.zenithblue.sambas3.input.GamepadMapper
import com.zenithblue.sambas3.utils.InputBindingPrefs
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.runtime.mutableStateOf

private enum class EmulatorActivityFinishReason {
    None,
    ExplicitExit,
    RecoveryRecreate,
    BootFailure,
    SystemLifecycle
}

private enum class FrontendHomeInputSource {
    TouchPs,
    PhysicalGuide,
    NativeFrontendEvent,
    ToolbarHome,
    MenuCommand
}

/**
 * Android host adapter only: lifecycle, views, surface, frontend-listener
 * registration, physical input forwarding, and game boot ownership. All menu
 * session/state decisions live in [InGameMenuCoordinator].
 */
class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private var debugPadReceiver: DebugPadReceiver? = null
    private lateinit var surfaceLeaseManager: SurfaceLeaseManager
    private lateinit var originalGamePath: String
    private var recoverySavestatePath: String? = null
    private var userSavestatePath: String? = null
    private var userSavestateSlot: Int? = null
    private var bootMode: EmulatorBootMode = EmulatorBootMode.FreshGame
    private var recoveryTransitionActive = false
    private var recoveryRecreateRequested = false
    // Activity.recreate() is an internal recovery handoff, not a user exit.
    // Keep the durable marker and library identity intact across that destroy.
    private var isRecoveryRecreate = false
    private var finishReason = EmulatorActivityFinishReason.None
    private val interactionLock = InteractionLock()
    private var operationUiState: SavestateOperationUiState = SavestateOperationUiState.Hidden
    private val activityInstanceId = NEXT_ACTIVITY_ID.incrementAndGet()
    private val crashReportState = mutableStateOf<CrashReport?>(null)
    private var transitionBitmap: Bitmap? = null
    private val transitionController = SavestateTransitionController()
    private lateinit var thumbnailStore: SavestateThumbnailStore
    private val bootMutex = Any()
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }
    private val gamepadMapper by lazy { GamepadMapper(ControllerProfileRepository.load()) }

    private val physicalTracker = PhysicalInputTracker()
    private val frontendHomeKeyGate = FrontendHomeKeyGate()
    private val inputGate = GameplayInputGate(physicalTracker)
    private val coreGateway: InGameMenuCoreGateway by lazy { RpcsxInGameMenuCoreGateway(RpcsxBridgeAdapter()) }
    private lateinit var coordinator: InGameMenuCoordinator
    private lateinit var menuInputRouter: InGameMenuInputRouter
    private lateinit var monitoringRepository: MonitoringRepository

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialogQueue.hostsSuppressed = true
        try { LogMonitor.start(this) } catch (_: Exception) {}
        try { com.zenithblue.sambas3.utils.GeneralSettings.init(this) } catch (_: Exception) {}
        if (RPCSX.rootDirectory.isEmpty()) {
            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null)?.toString()?.let { if (it.endsWith("/")) it else "$it/" } ?: ""
            try { com.zenithblue.sambas3.utils.FileUtil.fixNestedGameDirs(RPCSX.rootDirectory) } catch (_: Exception) {}
        }
        if (!RPCSX.initialized) {
            try { LogMonitor.start(this) } catch (_: Exception) {}
            try {
                RPCSX.nativeLibDirectory = packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
                if (RPCSX.openLibrary()) {
                    RPCSX.instance.initialize(RPCSX.rootDirectory, UserRepository.getUserFromSettings())
                    RPCSX.initialized = true
                    Log.i("RPCSX-UI", "RPCSX cold init via RPCSXActivity (nativeLib=${RPCSX.nativeLibDirectory})")
                    thread { try { RPCSX.instance.startMainThreadProcessor() } catch (e: Exception) { Log.w("RPCSX-UI", "startMainThreadProcessor cold failed ${e.message}") } }
                    thread { try { RPCSX.instance.processCompilationQueue() } catch (e: Exception) { Log.w("RPCSX-UI", "processCompilationQueue cold failed ${e.message}") } }
                } else {
                    Log.e("RPCSX-UI", "RPCSX cold openLibrary failed at ${RPCSX.nativeLibDirectory}")
                }
            } catch (e: Exception) {
                Log.e("RPCSX-UI", "RPCSX cold init failed: ${e.message}", e)
            }
        }
        try {
            NotificationChannels.ensureCreated(this)
            CompileProgressBridge.registerOnce(this)
        } catch (e: Exception) {
            Log.w("RPCSX-UI", "CompileProgressBridge register failed: ${e.message}")
        }

        coordinator = InGameMenuCoordinator(
            scope = lifecycleScope,
            core = coreGateway
        )
        thumbnailStore = SavestateThumbnailStore(this, lifecycleScope)
        menuInputRouter = InGameMenuInputRouter(onCommand = { command -> handleMenuCommand(command) })

        binding = ActivityRpcs3Binding.inflate(layoutInflater)
        setContentView(binding.root)
        surfaceLeaseManager = SurfaceLeaseManager(binding.surfaceHost)
        surfaceLeaseManager.onFailure = { reason ->
            runOnUiThread {
                if (recoveryTransitionActive) requestRecoveryRecreate(reason)
                else Log.e("S3SURFACE", "native surface lifecycle failed reason=$reason")
            }
        }
        surfaceLeaseManager.installInitial()

        monitoringRepository = MonitoringRepository(this)
        binding.monitoringOverlay.visibility = View.GONE
        binding.monitoringOverlay.isClickable = false
        binding.monitoringOverlay.isFocusable = false
        binding.monitoringOverlay.setOnTouchListener { _, _ -> false }
        monitoringRepository.start(lifecycleScope)
        binding.monitoringOverlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.monitoringOverlay.setContent {
            RPCSXTheme {
                val monitorSettings by MonitoringOverlaySettings.state(this@RPCSXActivity).collectAsStateWithLifecycle()
                val menuState by coordinator.state.collectAsStateWithLifecycle()
                LaunchedEffect(monitorSettings.enabled) {
                    binding.monitoringOverlay.visibility = if (monitorSettings.enabled) View.VISIBLE else View.GONE
                    Log.i("S3PERF", "monitor enabled=${monitorSettings.enabled} intervalMs=${monitorSettings.updateMs}")
                }
                MonitoringOverlay(monitoringRepository, monitorSettings, menuState.isOpen)
            }
        }

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()

        binding.ingameOverlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.ingameOverlay.translationZ = 64f
        binding.ingameOverlay.setContent {
            RPCSXTheme {
                val report = crashReportState.value
                if (report != null) {
                    GameCrashScreen(
                        report = report,
                        onRetry = { restartAfterFault(false) },
                        onSafeRetry = { restartAfterFault(true) },
                        onContinue = { restartAfterFaultFromLatestSave() },
                        onChooseSave = { restartAfterFaultFromLatestSave() },
                        onConfigure = { crashReportState.value = null; interactionLock.unlock() },
                        onExit = {
                            EmulationSessionJournal.clear(this@RPCSXActivity)
                            finishReason = EmulatorActivityFinishReason.BootFailure
                            finish()
                        }
                    )
                } else {
                    val uiState by coordinator.state.collectAsStateWithLifecycle()
                    InGameMenuHost(
                        uiState = uiState,
                        gamePath = intent.getStringExtra("path"),
                        core = coreGateway,
                        onIntent = coordinator::dispatch
                    )
                }
            }
        }

        // Host effects: coordinator decides, Activity only applies Android-side changes.
        coordinator.effects
            .onEach { effect ->
                when (effect) {
                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.ShowOverlay -> {
                        binding.ingameOverlay.visibility = View.VISIBLE
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.HideOverlay -> {
                        binding.ingameOverlay.visibility = View.GONE
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.EnterPadMenuMode -> {
                        binding.padOverlay.setMenuMode(true)
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.ExitPadMenuMode -> {
                        binding.padOverlay.setMenuMode(false)
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.WaitForPhysicalNeutralThenArmGameplay -> {
                        neutralizeForwardedPad()
                        inputGate.waitForNeutral()
                        menuInputRouter.cancelRepeat()
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.ArmGameplayNow -> inputGate.arm()

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.FinishGameActivity -> {
                        finishReason = EmulatorActivityFinishReason.ExplicitExit
                        thread {
                            var attempts = 0
                            while (attempts < 100) {
                                val s = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                                if (s == EmulatorState.Stopped) break
                                Thread.sleep(100)
                                attempts++
                            }
                            RPCSX.state.value = EmulatorState.Stopped
                            RPCSX.activeGame.value = null
                            EmulationSessionJournal.markCleanStop(this@RPCSXActivity)
                            runOnUiThread { finish() }
                        }
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.BeginSavestateTransition -> {
                        if (!effect.suspendMode) {
                            val titleId = runCatching { RPCSX.instance.getTitleId() }.getOrDefault("")
                            val requestId = PendingSavestateRecoveryStore.request(
                                this@RPCSXActivity,
                                effect.slot,
                                originalGamePath,
                                effect.preSaveMtimeMs,
                                effect.preSaveSizeBytes,
                                effect.savestatePath,
                                titleId
                            )
                            if (!transitionController.begin(requestId, effect.slot)) {
                                thumbnailStore.discard(requestId)
                                PendingSavestateRecoveryStore.markRequestFailure(this@RPCSXActivity, "transition-busy")
                                Log.e("S3SAVE", "transition controller rejected requestId=$requestId slot=${effect.slot}")
                                return@onEach
                            }
                            recoveryTransitionActive = true
                            interactionLock.lock(EmulatorInteractionLock.SavestateSaving)
                            operationUiState = SavestateOperationUiState.Saving(effect.slot, "Capturing emulator state...")
                            thumbnailStore.begin(requestId, effect.slot, effect.savestatePath)
                            EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.SAVING)
                            showTransitionOverlay("Saving Slot ${effect.slot}...")
                            captureTransitionFrame(requestId, effect.slot)
                            neutralizeForwardedPad()
                            Log.i("S3SAVE", "transition begin requestId=$requestId slot=${effect.slot} original=$originalGamePath")
                        }
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.SavestateRequestAccepted -> {
                        val pending = PendingSavestateRecoveryStore.read(this@RPCSXActivity)
                        Log.i(
                            "S3SAVE",
                            "native-accepted requestId=${pending?.requestId ?: 0L} slot=${effect.slot} " +
                            "state=${pending?.state ?: "missing"}"
                        )
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.BeginSavestateLoadTransition -> {
                        if (interactionLock.lock(EmulatorInteractionLock.SavestateLoading)) {
                            recoveryTransitionActive = true
                            operationUiState = SavestateOperationUiState.Loading(effect.slot, effect.previewPath, "Preparing saved state...")
                            EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.LOADING)
                            neutralizeForwardedPad()
                            binding.padOverlay.setMenuMode(true)
                            showTransitionOverlay("Loading Slot ${effect.slot}...")
                            Log.i("S3SAVE", "manual-load transition begin slot=${effect.slot} path=${effect.savestatePath}")
                        }
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.SavestateLoadAccepted -> {
                        val titleId = runCatching { RPCSX.instance.getTitleId() }.getOrDefault("")
                        val record = PendingSavestateRecoveryStore.armCommitted(
                            this@RPCSXActivity,
                            effect.slot,
                            originalGamePath,
                            effect.savestatePath,
                            titleId
                        )
                        if (record == null) {
                            Log.w("S3SAVE", "manual-load recovery marker not armed slot=${effect.slot}")
                        } else {
                            operationUiState = SavestateOperationUiState.Loading(effect.slot, null, "Restoring saved state...")
                            watchManualLoad(record)
                        }
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.SavestateTransitionFailed -> {
                        PendingSavestateRecoveryStore.markRequestFailure(this@RPCSXActivity, effect.reason)
                        failTransition(effect.reason)
                        Log.e("S3SAVE", "request failed reason=${effect.reason}")
                    }
                }
            }
            .launchIn(lifecycleScope)

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleAndroidBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        debugPadReceiver = DebugPadReceiver.register(this)

        binding.oscToggle.setOnClickListener {
            if (interactionLock.isLocked()) return@setOnClickListener
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        binding.menuToggle.setOnClickListener {
            if (interactionLock.isLocked()) return@setOnClickListener
            requestHomeToggle(FrontendHomeInputSource.ToolbarHome)
        }

        val pendingRecovery = PendingSavestateRecoveryStore.validForLaunch(this)
        pendingRecovery?.let {
            thumbnailStore.recoverCommitted(it.requestId, it.slot, it.savestatePath)
        }
        bootMode = intent.getStringExtra(EXTRA_BOOT_MODE)?.let {
            runCatching { EmulatorBootMode.valueOf(it) }.getOrNull()
        } ?: EmulatorBootMode.FreshGame
        originalGamePath = intent.getStringExtra(EXTRA_ORIGINAL_GAME_PATH)
            ?: intent.getStringExtra("path")
            ?: pendingRecovery?.originalGamePath
            ?: error("RPCSXActivity requires an original game path")
        recoverySavestatePath = intent.getStringExtra(EXTRA_SAVESTATE_PATH)
            ?.takeIf { bootMode == EmulatorBootMode.DurableRecovery }
            ?: intent.getStringExtra(EXTRA_RECOVERY_SAVESTATE)
            ?: pendingRecovery?.takeIf { it.originalGamePath == originalGamePath }?.savestatePath
        userSavestatePath = intent.getStringExtra(EXTRA_SAVESTATE_PATH)
            ?.takeIf { bootMode == EmulatorBootMode.UserSelectedSavestate }
            ?: intent.getStringExtra(EXTRA_USER_SAVESTATE)
            ?.takeIf { it.isNotBlank() && java.io.File(it).isFile }
        userSavestateSlot = intent.getIntExtra(EXTRA_SAVESTATE_SLOT,
            intent.getIntExtra(EXTRA_USER_SAVESTATE_SLOT, -1))
            .takeIf { it >= 0 }
        if (bootMode == EmulatorBootMode.FreshGame && recoverySavestatePath != null) {
            bootMode = EmulatorBootMode.DurableRecovery
        } else if (bootMode == EmulatorBootMode.FreshGame && userSavestatePath != null) {
            bootMode = EmulatorBootMode.UserSelectedSavestate
        }
        val gamePath = originalGamePath
        RPCSX.lastPlayedGame = gamePath
        EmulationSessionJournal.begin(
            this,
            originalGamePath,
            GameIdentity.titleIdOrNull(originalGamePath, null),
            originalGamePath.substringAfterLast('/'),
            activityInstanceId,
            surfaceLeaseManager.currentGeneration
        )
        logSessionSnapshot("activity-created")
        if (recoverySavestatePath != null) {
            pendingRecovery?.let {
                transitionController.beginRecoveryBoot(it.requestId, it.slot, it.savestatePath)
            }
            recoveryTransitionActive = true
            showTransitionOverlay("Restoring...")
            interactionLock.lock(EmulatorInteractionLock.BootTransition)
            operationUiState = SavestateOperationUiState.Loading(pendingRecovery?.slot ?: 0, null, "Restoring saved state...")
        }

        // Frontend listener registered only after binding + content are ready (§16).
        registerFrontendListener()

        bootThread = thread {
            EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.BOOTING, surfaceLeaseManager.currentGeneration)
            val stateBeforeBoot = runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)
            Log.i("S3HOMELOAD", "preflight mode=$bootMode state=$stateBeforeBoot original=$gamePath savestate=${userSavestatePath ?: recoverySavestatePath}")
            Log.i("S3RECOVERY", "preflight state=$stateBeforeBoot activeGame=${RPCSX.activeGame.value} recovery=${recoverySavestatePath != null}")
            if (recoverySavestatePath == null && stateBeforeBoot == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
                RPCSX.instance.resume()
                return@thread
            }
            if (stateBeforeBoot != EmulatorState.Stopped) {
                val stopResult = kotlinx.coroutines.runBlocking {
                    CoreRecoveryCoordinator.ensureStoppedForFreshBoot(
                        reason = "RPCSXActivity boot",
                        state = { RPCSX.getState() },
                        kill = { RPCSX.instance.kill() },
                        onLog = { Log.i("S3RECOVERY", it) }
                    )
                }
                if (stopResult != StopResult.AlreadyStopped && stopResult != StopResult.Stopped) {
                    Log.e("S3RECOVERY", "preflight refused boot result=$stopResult")
                    runOnUiThread { showInProcessFault("Core did not reach Stopped before boot ($stopResult)") }
                    return@thread
                }
            }
            Log.i("S3HOMELOAD", "stopped mode=$bootMode state=${runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)}")

            Log.i("S3HOMELOAD", "surface-ready-wait mode=$bootMode")
            val surfaceReady = kotlinx.coroutines.runBlocking {
                surfaceLeaseManager.awaitReady()
            }
            if (surfaceReady != SurfaceReadyResult.Ready) {
                Log.e("S3HOMELOAD", "surface-ready failed result=$surfaceReady generation=${surfaceLeaseManager.currentGeneration}")
                runOnUiThread { showInProcessFault("surface-ready-$surfaceReady") }
                return@thread
            }
            if (bootMode == EmulatorBootMode.UserSelectedSavestate) {
                Log.i("S3HOMELOAD", "surface-ready gen=${surfaceLeaseManager.currentGeneration}")
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            val isRecoveryBoot = bootMode == EmulatorBootMode.DurableRecovery
            if (isRecoveryBoot && !PendingSavestateRecoveryStore.markBooting(this@RPCSXActivity)) {
                Log.e("S3SAVE", "recovery boot refused after retry limit")
                PendingSavestateRecoveryStore.markFailure(this@RPCSXActivity, "retry-limit")
                runOnUiThread {
                    AlertDialogQueue.showDialog(
                        "Saved-state recovery stopped",
                        "The saved slot was kept, but automatic recovery stopped after repeated failures. " +
                            "You can load it manually from the save-state menu."
                    )
                    finishReason = EmulatorActivityFinishReason.BootFailure
                    finish()
                }
                return@thread
            }
            val bootPath = when (bootMode) {
                EmulatorBootMode.DurableRecovery -> recoverySavestatePath
                EmulatorBootMode.UserSelectedSavestate -> userSavestatePath
                EmulatorBootMode.FreshGame -> gamePath
            } ?: gamePath
            if (bootMode != EmulatorBootMode.FreshGame &&
                (bootPath.isBlank() || !java.io.File(bootPath).isFile)
            ) {
                Log.e("S3HOMELOAD", "direct-boot failed reason=missing-savestate path=$bootPath")
                runOnUiThread { showInProcessFault("missing-savestate-$bootPath") }
                return@thread
            }
            val preBootTitleId = GameSettingsOverrides.resolveTitleId(gamePath, this@RPCSXActivity)
            GameSettingsOverrides.applyForGame(this@RPCSXActivity, preBootTitleId)
            if (intent.getBooleanExtra(EXTRA_SAFE_RETRY, false)) {
                // Safe retry is transient and must not overwrite the user's driver choice.
                runCatching {
                    RPCSX.instance.setCustomDriver("", "", applicationInfo.nativeLibraryDir)
                }.onFailure { Log.w("S3CRASH", "safe retry driver setup failed: ${it.message}") }
                Log.i("S3CRASH", "safe retry using system Vulkan driver")
            }

            Log.i("S3HOMELOAD", "direct-boot-begin mode=$bootMode source=$bootPath original=$gamePath")
            val bootResult = BootResult.fromInt(
                if (bootMode != EmulatorBootMode.FreshGame) {
                    bootSavestateSerialized(bootPath, gamePath)
                } else {
                    bootSerialized(bootPath)
                }
            )
            if (bootResult != BootResult.NoErrors) {
                Log.w("S3HOMELOAD", "direct-boot-return result=$bootResult source=$bootPath")
                if (isRecoveryBoot) PendingSavestateRecoveryStore.markFailure(this@RPCSXActivity, bootResult.name)
                else RPCSX.activeGame.value = null
                try { RPCSX.state.value = RPCSX.getState() } catch (_: Exception) {}
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finishReason = EmulatorActivityFinishReason.BootFailure
                finish()
            } else {
                RPCSX.activeGame.value = gamePath
                EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.RUNNING, surfaceLeaseManager.currentGeneration)
                logSessionSnapshot("boot-return")
                Log.i("S3HOMELOAD", "direct-boot-return result=NoErrors state=${RPCSX.getState()}")
                if (bootMode != EmulatorBootMode.FreshGame) confirmRecoveryFrameAndClear()
                pollAndLearnTitleId(gamePath)
            }
        }
    }

    private fun registerFrontendListener() {
        try {
            RPCSX.instance.setFrontendEventListener { type, payload ->
                when (type) {
                    RPCSX.FRONTEND_EVENT_HOME_REQUESTED -> runOnUiThread {
                        // Queued until host is ready; binding is guaranteed here (§16).
                        handleFrontendHomeRequest()
                    }

                    RPCSX.FRONTEND_EVENT_SCREENSHOT_RESULT -> runOnUiThread {
                        val msg = if (payload.isNullOrBlank()) {
                            getString(R.string.screenshot_failed)
                        } else {
                            getString(R.string.screenshot_saved, payload.substringAfterLast('/'))
                        }
                        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }

                    RPCSX.FRONTEND_EVENT_SAVESTATE_COMMITTED -> runOnUiThread {
                        handleSavestateCommitted(payload)
                    }

                    RPCSX.FRONTEND_EVENT_SAVESTATE_FAILED -> runOnUiThread {
                        PendingSavestateRecoveryStore.markRequestFailure(this, payload ?: "native-save-failed")
                        failTransition(payload ?: "native-save-failed")
                        Log.e("S3SAVE", "completion failed payload=$payload")
                    }

                    RPCSX.FRONTEND_EVENT_TROPHY_UNLOCKED -> runOnUiThread { TrophyEvents.notifyUnlocked() }

                    RPCSX.FRONTEND_EVENT_RENDERER_ERROR,
                    RPCSX.FRONTEND_EVENT_EMULATOR_ACTION_ERROR -> runOnUiThread {
                        if (recoveryTransitionActive) {
                            requestRecoveryRecreate(payload ?: "renderer-error")
                        } else {
                            showInProcessFault(payload ?: "native renderer/action error")
                        }
                    }
                }
            }
            Log.i("RPCSX-UI", "FrontendEventListener registered")
        } catch (e: Exception) {
            Log.w("RPCSX-UI", "FrontendEventListener failed ${e.message}")
        }
    }

    private fun handleFrontendHomeRequest() {
        requestHomeToggle(FrontendHomeInputSource.NativeFrontendEvent)
    }

    private fun showInProcessFault(evidence: String) {
        if (crashReportState.value != null) return
        interactionLock.lock(EmulatorInteractionLock.CrashView)
        EmulationSessionJournal.update(this, EmulationSessionState.FAILED)
        Log.e("S3CRASH", "in-process fault evidence=$evidence")
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val report = runCatching { CrashEvidenceCollector.collectSummary(this@RPCSXActivity, EmulationSessionJournal.read(this@RPCSXActivity), evidence) }.getOrNull()
            runOnUiThread {
                if (report != null) {
                    crashReportState.value = report
                    binding.ingameOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun restartAfterFault(safe: Boolean) {
        val path = originalGamePath
        thread {
            val stopResult = kotlinx.coroutines.runBlocking {
                CoreRecoveryCoordinator.ensureStoppedForFreshBoot(
                    reason = "crash retry",
                    state = { RPCSX.getState() },
                    kill = { RPCSX.instance.kill() },
                    onLog = { Log.i("S3RECOVERY", it) }
                )
            }
            if (stopResult == StopResult.AlreadyStopped || stopResult == StopResult.Stopped) {
                runOnUiThread {
                    finishReason = EmulatorActivityFinishReason.BootFailure
                    startActivity(Intent(this, RPCSXActivity::class.java).apply {
                        putExtra("path", path)
                        putExtra(EXTRA_SAFE_RETRY, safe)
                    })
                    finish()
                }
            } else {
                Log.e("S3RECOVERY", "retry refused result=$stopResult")
            }
        }
    }

    private fun restartAfterFaultFromLatestSave() {
        val path = originalGamePath
        val game = com.zenithblue.sambas3.GameRepository.list().firstOrNull { it.info.path == path }
        val slot = game?.let {
            GameSavestateRepository.slots(this, it)
                .filter { save -> save.exists && save.path != null }
                .maxByOrNull { save -> save.mtimeMs }
        }
        if (slot?.path == null) {
            restartAfterFault(safe = false)
            return
        }
        thread {
            val stopResult = kotlinx.coroutines.runBlocking {
                CoreRecoveryCoordinator.ensureStoppedForFreshBoot(
                    reason = "crash continue save",
                    state = { RPCSX.getState() },
                    kill = { RPCSX.instance.kill() },
                    onLog = { Log.i("S3RECOVERY", it) }
                )
            }
            if (stopResult == StopResult.AlreadyStopped || stopResult == StopResult.Stopped) {
                runOnUiThread {
                    finishReason = EmulatorActivityFinishReason.BootFailure
                    startActivity(Intent(this, RPCSXActivity::class.java).apply {
                        putExtra("path", path)
                        putExtra(EXTRA_USER_SAVESTATE, slot.path)
                        putExtra(EXTRA_USER_SAVESTATE_SLOT, slot.slot)
                    })
                    finish()
                }
            } else {
                Log.e("S3RECOVERY", "continue save refused result=$stopResult")
            }
        }
    }

    private fun requestHomeToggle(source: FrontendHomeInputSource) {
        if (recoveryTransitionActive) {
            Log.i("S3HOME", "ignored source=$source reason=recovery-transition")
            return
        }

        when (val session = coordinator.state.value.session) {
            MenuSessionState.Opening,
            is MenuSessionState.Closing -> {
                Log.i("S3HOME", "duplicate ignored source=$source session=$session")
                return
            }
            MenuSessionState.Closed -> {
                val state = runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)
                if (state != EmulatorState.Running && state != EmulatorState.Paused) {
                    Log.w("S3HOME", "ignored source=$source state=$state")
                    return
                }
                Log.i("S3HOME", "source=$source edge=down action=open state=$state")
                coordinator.dispatch(InGameMenuIntent.Open)
            }
            is MenuSessionState.Open -> {
                Log.i("S3HOME", "source=$source edge=down action=resume")
                coordinator.dispatch(InGameMenuIntent.Resume)
            }
        }
    }

    private fun handleAndroidBack() {
        val state = runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)
        when (resolveAndroidBackAction(recoveryTransitionActive, coordinator.state.value.isOpen, state)) {
            AndroidBackAction.Consume -> Log.i("S3BACK", "consumed recovery=$recoveryTransitionActive state=$state")
            AndroidBackAction.DispatchMenuBack -> {
                Log.i("S3BACK", "menu -> coordinator.Back")
                coordinator.dispatch(InGameMenuIntent.Back)
            }
            AndroidBackAction.OpenMenu -> {
                Log.i("S3BACK", "gameplay -> open-menu state=$state")
                coordinator.dispatch(InGameMenuIntent.Open)
            }
            AndroidBackAction.FinishActivity -> {
                finishReason = EmulatorActivityFinishReason.SystemLifecycle
                Log.i("S3BACK", "stopped -> finish")
                finish()
            }
        }
    }

    private fun handleSavestateCommitted(payload: String?) {
        val committed = payload ?: return
        val record = PendingSavestateRecoveryStore.commit(this, committed)
        val path = record?.savestatePath ?: run {
            // Native completion can arrive late after the transition already
            // cleared its marker.  It is stale, not a new save failure.
            Log.w("S3SAVE", "completion ignored: no matching pending request payload=$payload")
            return
        }
        if (path.isBlank() || !java.io.File(path).isFile) {
            Log.e("S3SAVE", "completion rejected: invalid committed payload=$payload")
            return
        }
        if (!recoveryTransitionActive) {
            Log.w("S3SAVE", "completion arrived without active transition requestId=${record.requestId}")
            return
        }
        if (!transitionController.committed(record.requestId, record.slot, path)) {
            Log.w("S3SAVE", "completion rejected by transition controller requestId=${record.requestId} slot=${record.slot}")
            return
        }
        thumbnailStore.commit(record.requestId, record.slot, path)
        operationUiState = SavestateOperationUiState.Saving(record.slot, "Finalizing Slot ${record.slot}...")
        EmulationSessionJournal.update(this, EmulationSessionState.LOADING)
        if (!transitionController.surfaceResetStarted()) {
            Log.w("S3SURFACE", "surface reset rejected requestId=${record.requestId}")
            return
        }
        operationUiState = SavestateOperationUiState.Loading(record.slot, SavestateThumbnailStore.previewPathForPath(path).path, "Restoring saved state...")
        binding.transitionLabel.text = "Restoring Slot ${record.slot}..."
        Log.i("S3RENDER", "old surface replacement requested requestId=${record.requestId} slot=${record.slot} path=$path")
        runCatching {
            surfaceLeaseManager.replace {
                if (!transitionController.surfaceReady(record.requestId, record.slot)) {
                    Log.w("S3SURFACE", "fresh surface ready rejected requestId=${record.requestId} generation=${surfaceLeaseManager.currentGeneration}")
                    return@replace
                }
                bootExactSavestate(record)
            }
        }.onFailure {
            PendingSavestateRecoveryStore.markFailure(this, it.message ?: "surface-replace-failed")
            failTransition(it.message ?: "surface-replace-failed")
            Log.e("S3SURFACE", "surface replacement failed", it)
        }
    }

    private fun bootExactSavestate(record: PendingSavestateRecovery) {
        if (!PendingSavestateRecoveryStore.markBooting(this)) {
            Log.e("S3SAVE", "saved-slot boot skipped after retry limit requestId=${record.requestId}")
            failTransition("retry-limit")
            return
        }
        if (!transitionController.bootStarted(record.requestId, record.slot)) {
            Log.e("S3SAVE", "saved-slot boot rejected by transition controller requestId=${record.requestId}")
            PendingSavestateRecoveryStore.markFailure(this, "controller-boot-rejected")
            failTransition("controller-boot-rejected")
            return
        }
        bootThread?.interrupt()
        bootThread = thread(name = "S3 Saved-state Boot") {
            Log.i("S3RENDER", "boot-savestate-begin requestId=${record.requestId} generation=${surfaceLeaseManager.currentGeneration} path=${record.savestatePath}")
            val result = BootResult.fromInt(
                bootSavestateSerialized(record.savestatePath, originalGamePath)
            )
            if (result != BootResult.NoErrors) {
                PendingSavestateRecoveryStore.markFailure(this@RPCSXActivity, result.name)
                runOnUiThread {
                    failTransition(result.name)
                }
                Log.e("S3RENDER", "boot-savestate-return failed requestId=${record.requestId} result=$result")
                return@thread
            }
            RPCSX.activeGame.value = originalGamePath
            Log.i("S3RENDER", "boot-savestate-return requestId=${record.requestId} state=${RPCSX.getState()}")
            runOnUiThread { confirmRecoveryFrameAndClear() }
        }
    }

    private fun confirmRecoveryFrameAndClear() {
        thread(name = "S3 Saved-state Frame Confirm") {
            var stable = 0
            val expectedGeneration = surfaceLeaseManager.currentGeneration
            val deadline = System.currentTimeMillis() + 30_000L
            while (stable < 6 && System.currentTimeMillis() < deadline && !Thread.interrupted()) {
                val copied = probeCurrentFrame(expectedGeneration)
                if (RPCSX.getState() == EmulatorState.Running && copied) stable++ else stable = 0
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            if (stable < 6) {
                Log.e("S3RENDER", "first-frame-confirm-timeout generation=${surfaceLeaseManager.currentGeneration}")
                runOnUiThread {
                    requestRecoveryRecreate("first-frame-timeout")
                }
                return@thread
            }
            val record = PendingSavestateRecoveryStore.read(this@RPCSXActivity)
            if (record != null && !transitionController.firstFrameConfirmed(record.requestId, record.slot)) {
                Log.e("S3RENDER", "first-frame confirmation rejected by transition controller")
                return@thread
            }
            Log.i("S3RENDER", "running generation=${surfaceLeaseManager.currentGeneration}")
            Log.i("S3RENDER", "first-frame-confirmed generation=${surfaceLeaseManager.currentGeneration}")
            Log.i("S3HOMELOAD", "running mode=$bootMode generation=${surfaceLeaseManager.currentGeneration}")
            Log.i("S3HOMELOAD", "first-frame-confirmed mode=$bootMode generation=${surfaceLeaseManager.currentGeneration}")
            runCatching { RPCSX.instance.clearSavestateProgress() }
            PendingSavestateRecoveryStore.clear(this@RPCSXActivity)
            runOnUiThread {
                binding.transitionOverlay.animate()
                    .alpha(0f)
                    .setDuration(160L)
                    .withEndAction {
                        binding.transitionOverlay.visibility = View.GONE
                        binding.transitionOverlay.alpha = 1f
                        binding.transitionFrame.setImageDrawable(null)
                        binding.transitionFrame.visibility = View.GONE
                        transitionBitmap?.recycle()
                        transitionBitmap = null
                        recoveryTransitionActive = false
                        interactionLock.unlock()
                        operationUiState = SavestateOperationUiState.Hidden
                        EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.RUNNING)
                        inputGate.waitForNeutral()
                        Log.i("S3RENDER", "transition-overlay-hidden")
                    }
                    .start()
            }
        }
    }

    /**
     * Manual LOAD uses the existing native transition, but still needs the
     * same durable recovery guarantee and boot-owned progress cleanup as the
     * automatic save->restore path.  Running plus a stable PixelCopy frame is
     * the completion gate; a process death before that gate leaves COMMITTED
     * armed for the next Activity launch.
     */
    private fun watchManualLoad(record: PendingSavestateRecovery) {
        bootThread?.interrupt()
        bootThread = thread(name = "S3 Manual Savestate Confirm") {
            val expectedGeneration = surfaceLeaseManager.currentGeneration
            val deadline = System.currentTimeMillis() + 60_000L
            val notBefore = System.currentTimeMillis() + 3_000L
            var stable = 0
            while (System.currentTimeMillis() < deadline && !Thread.interrupted()) {
                val running = runCatching { RPCSX.getState() == EmulatorState.Running }.getOrDefault(false)
                val copied = if (running && System.currentTimeMillis() >= notBefore) {
                    probeCurrentFrame(expectedGeneration)
                } else {
                    false
                }
                if (running && copied) stable++ else stable = 0
                if (stable >= 6) {
                    runCatching { RPCSX.instance.clearSavestateProgress() }
                    PendingSavestateRecoveryStore.clear(this@RPCSXActivity)
                    Log.i(
                        "S3SAVE",
                        "manual-load first-frame-confirmed slot=${record.slot} generation=$expectedGeneration"
                    )
                    runOnUiThread {
                        recoveryTransitionActive = false
                        operationUiState = SavestateOperationUiState.Hidden
                        interactionLock.unlock()
                        binding.transitionOverlay.animate().alpha(0f).setDuration(160L).withEndAction {
                            binding.transitionOverlay.visibility = View.GONE
                            binding.transitionOverlay.alpha = 1f
                            EmulationSessionJournal.update(this@RPCSXActivity, EmulationSessionState.RUNNING)
                            inputGate.waitForNeutral()
                        }.start()
                    }
                    return@thread
                }
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            if (!Thread.interrupted()) {
                Log.e(
                    "S3SAVE",
                    "manual-load confirmation timeout slot=${record.slot}; exact slot kept for recovery"
                )
            }
        }
    }

    private fun showTransitionOverlay(label: String) {
        binding.transitionLabel.text = label
        binding.transitionOverlay.alpha = 1f
        binding.transitionOverlay.visibility = View.VISIBLE
    }

    /** Capture the currently displayed game frame before the old surface is released. */
    private fun captureTransitionFrame(requestId: Long, slot: Int) {
        val frame = surfaceLeaseManager.currentFrame ?: run {
            thumbnailStore.captureFailed(requestId, slot)
            Log.w("S3THUMB", "capture-failed request=$requestId reason=no-frame")
            return
        }
        requestFrameCopy(frame) { bitmap, success ->
            if (success && bitmap != null && recoveryTransitionActive) {
                thumbnailStore.stage(requestId, slot, bitmap)
                transitionBitmap?.recycle()
                transitionBitmap = bitmap
                binding.transitionFrame.setImageBitmap(bitmap)
                binding.transitionFrame.visibility = View.VISIBLE
                Log.i("S3RENDER", "transition-frame-captured generation=${frame.generation} ${bitmap.width}x${bitmap.height}")
            } else {
                thumbnailStore.captureFailed(requestId, slot)
                bitmap?.recycle()
                Log.w("S3THUMB", "capture-failed request=$requestId generation=${frame.generation}")
            }
        }
    }

    /**
     * PixelCopy is also the first-frame proof: Running alone can be true while
     * the renderer thread has already lost its BufferQueue.
     */
    private fun probeCurrentFrame(expectedGeneration: Long): Boolean {
        val latch = CountDownLatch(1)
        var success = false
        runOnUiThread {
            val frame = surfaceLeaseManager.currentFrame
            if (frame == null || frame.generation != expectedGeneration) {
                latch.countDown()
            } else {
                requestFrameCopy(frame) { bitmap, copied ->
                    bitmap?.recycle()
                    success = copied && frame.generation == surfaceLeaseManager.currentGeneration
                    latch.countDown()
                }
            }
        }
        return try {
            latch.await(FRAME_COPY_TIMEOUT_MS, TimeUnit.MILLISECONDS) && success
        } catch (_: InterruptedException) {
            false
        }
    }

    private fun requestFrameCopy(frame: GraphicsFrame, callback: (Bitmap?, Boolean) -> Unit) {
        runOnUiThread {
            if (frame !== surfaceLeaseManager.currentFrame || frame.width <= 0 || frame.height <= 0) {
                callback(null, false)
                return@runOnUiThread
            }
            val maxLongEdge = 1920
            val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(frame.width, frame.height).toFloat())
            val width = maxOf(1, (frame.width * scale).toInt())
            val height = maxOf(1, (frame.height * scale).toInt())
            val bitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Log.e("S3RENDER", "transition-frame allocation failed", e)
                callback(null, false)
                return@runOnUiThread
            }
            PixelCopy.request(
                frame,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        callback(bitmap, true)
                    } else {
                        bitmap.recycle()
                        callback(null, false)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    private fun failTransition(reason: String) {
        val transitionState = transitionController.state
        if (transitionState.phase == SavestateTransitionController.Phase.Saving) {
            thumbnailStore.discard(transitionState.requestId)
        }
        transitionController.fail(reason)
        transitionBitmap?.recycle()
        transitionBitmap = null
        binding.transitionFrame.setImageDrawable(null)
        binding.transitionFrame.visibility = View.GONE
        binding.transitionOverlay.visibility = View.GONE
        recoveryTransitionActive = false
        interactionLock.unlock()
        operationUiState = SavestateOperationUiState.Failed("SAVE/LOAD", transitionState.slot, reason)
        EmulationSessionJournal.update(this, EmulationSessionState.FAILED)
        binding.transitionLabel.postDelayed({
            operationUiState = SavestateOperationUiState.Hidden
            binding.transitionOverlay.visibility = View.GONE
        }, 1800L)
    }

    /** One bounded Activity recreation is the last-resort recovery for a dead BufferQueue. */
    private fun requestRecoveryRecreate(reason: String) {
        if (!recoveryTransitionActive) return
        if (recoveryRecreateRequested) {
            PendingSavestateRecoveryStore.markFailure(this, reason)
            failTransition(reason)
            return
        }
        recoveryRecreateRequested = true
        isRecoveryRecreate = true
        finishReason = EmulatorActivityFinishReason.RecoveryRecreate
        PendingSavestateRecoveryStore.markFailure(this, reason)
        Log.e("S3RENDER", "recovery recreate requested reason=$reason")
        binding.transitionLabel.text = "Restoring..."
        recreate()
    }

    private fun bootSerialized(path: String): Int = synchronized(bootMutex) {
        RPCSX.instance.boot(path)
    }

    private fun bootSavestateSerialized(savestatePath: String, originalGamePath: String): Int =
        synchronized(bootMutex) {
            RPCSX.instance.bootSavestate(savestatePath, originalGamePath)
        }

    /** Map a semantic menu command to coordinator intents. Returns true if consumed. */
    private fun handleMenuCommand(command: MenuCommand): Boolean {
        return when (command) {
            is MenuCommand.Previous -> coordinator.moveSelection(-1)
            is MenuCommand.Next -> coordinator.moveSelection(1)
            is MenuCommand.PageUp -> coordinator.jumpSelection(-10)
            is MenuCommand.PageDown -> coordinator.jumpSelection(10)
            is MenuCommand.Activate -> {
                val intent = coordinator.activateSelectedIntent()
                if (intent != null) {
                    coordinator.dispatch(intent)
                    true
                } else {
                    false
                }
            }

            is MenuCommand.Back -> {
                coordinator.dispatch(InGameMenuIntent.Back)
                true
            }

            is MenuCommand.HomeToggle -> {
                requestHomeToggle(FrontendHomeInputSource.MenuCommand)
                true
            }

            // Page actions: SAVE (Square) / DISCARD (Triangle) on Settings; otherwise unconsumed.
            is MenuCommand.PageAction1 -> {
                if (coordinator.state.value.currentPage == com.zenithblue.sambas3.ui.ingame.InGamePage.Settings) {
                    coordinator.dispatch(InGameMenuIntent.SettingsSave)
                    true
                } else {
                    false
                }
            }

            is MenuCommand.PageAction2 -> {
                if (coordinator.state.value.currentPage == com.zenithblue.sambas3.ui.ingame.InGamePage.Settings) {
                    coordinator.dispatch(InGameMenuIntent.SettingsDiscard)
                    true
                } else {
                    false
                }
            }

            is MenuCommand.Left -> false
            is MenuCommand.Right -> false
        }
    }

    private fun neutralizeForwardedPad() {
        gamePadState = State()
        usesAxisL2 = false
        usesAxisR2 = false
        try {
            RPCSX.instance.overlayPadData(0, 0, 127, 127, 127, 127)
        } catch (_: Exception) {}
    }

    private fun pollAndLearnTitleId(gamePath: String) {
        var waitedMs = 0L
        while (waitedMs < TITLE_ID_POLL_TIMEOUT_MS) {
            if (Thread.interrupted()) return
            val titleId = try {
                RPCSX.instance.getTitleId()
            } catch (e: Exception) {
                ""
            }
            if (titleId.isNotBlank()) {
                GameSettingsOverrides.learnTitleId(this, gamePath, titleId)
                GameSettingsOverrides.applyTitleTier(this, titleId)
                return
            }
            try {
                Thread.sleep(TITLE_ID_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
            waitedMs += TITLE_ID_POLL_INTERVAL_MS
        }
    }

    override fun onDestroy() {
        if (::monitoringRepository.isInitialized) monitoringRepository.stop()
        transitionBitmap?.recycle()
        transitionBitmap = null
        try { surfaceLeaseManager.destroy() } catch (_: Exception) {}
        super.onDestroy()
        AlertDialogQueue.hostsSuppressed = false
        try { coordinator.closeForActivityDestroy() } catch (e: Exception) {
            Log.w("InGameMenu", "closeForActivityDestroy failed ${e.message}")
        }
        try { RPCSX.instance.setFrontendEventListener(null) } catch (_: Exception) {}
        try {
            val nativeState = RPCSX.getState()
            RPCSX.state.value = nativeState
            if (nativeState == EmulatorState.Stopped && !isRecoveryRecreate) {
                val myPath = try { intent.getStringExtra("path") } catch (_: Exception) { null }
                if (myPath != null && RPCSX.activeGame.value == myPath) {
                    Log.i("S3LIFE", "onDestroy Stopped clearing stale activeGame=$myPath")
                    RPCSX.activeGame.value = null
                } else if (myPath == null && RPCSX.activeGame.value != null) {
                    Log.w("S3LIFE", "onDestroy Stopped with activeGame=${RPCSX.activeGame.value} but no path, clearing")
                    RPCSX.activeGame.value = null
                }
            }
            Log.i(
                "S3LIFE",
                "RPCSXActivity.onDestroy isFinishing=$isFinishing " +
                    "isChangingConfigurations=$isChangingConfigurations finishReason=$finishReason " +
                    "nativeState=$nativeState activeGame=${RPCSX.activeGame.value} " +
                    "recoveryTransitionActive=$recoveryTransitionActive"
            )
            logSessionSnapshot("activity-destroyed")
            if (isFinishing &&
                finishReason == EmulatorActivityFinishReason.None &&
                (nativeState == EmulatorState.Running || nativeState == EmulatorState.Paused)
            ) {
                Log.e("S3LIFE", "S3LIFE unexpected Activity finish while emulator is alive")
            }
        } catch (e: Exception) {
            Log.w("S3LIFE", "onDestroy state sync failed: ${e.message}")
        }
        unregisterUsbEventListener()
        try { debugPadReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        bootThread?.interrupt()
        try { bootThread?.join(2000) } catch (_: Exception) {}
        try { if (bootThread?.isAlive == true) Log.w("S3LIFE", "bootThread still alive after onDestroy join timeout") } catch (_: Exception) {}
    }

    private fun keyCodeToPadBit(keyCode: Int): Pair<Int, Int> {
        val event = inputBindings[keyCode] ?: Pair(0, 0)
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (usesAxisR2) return Pair(0, 0) else return event
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (usesAxisL2) return Pair(0, 0) else return event
        }
        return event
    }

    private fun isMenuOpen(): Boolean = coordinator.state.value.isOpen

    private fun logSessionSnapshot(reason: String) {
        val nativeState = runCatching { RPCSX.getState() }.getOrDefault(EmulatorState.Stopped)
        val active = RPCSX.activeGame.value
        SessionStateReconciliation.invalidPairing(SessionStatePairing(nativeState, active))?.let {
            Log.w("S3SESSION", "invalid-pairing=$it reason=$reason native=$nativeState activeGame=$active")
        }
        Log.i("S3SESSION", "activity=$activityInstanceId reason=$reason native=$nativeState mirrored=${RPCSX.state.value} activeGame=$active surfaceGen=${runCatching { surfaceLeaseManager.currentGeneration }.getOrDefault(-1L)} recovery=$recoveryTransitionActive menu=${coordinator.state.value.session}")
    }

    private fun isFrontendHomeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (interactionLock.isLocked()) return true
        physicalTracker.onKeyEvent(keyCode, event?.action ?: KeyEvent.ACTION_UP)
        if (isFrontendHomeKey(keyCode)) {
            if (event == null || frontendHomeKeyGate.acceptDown(event.repeatCount)) {
                requestHomeToggle(FrontendHomeInputSource.PhysicalGuide)
            }
            return true
        }
        if (isMenuOpen()) {
            if (menuInputRouter.handleKey(keyCode, event?.action ?: -1, event)) return true
            if (event != null && (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) != 0) {
                return true
            }
        }
        // Gameplay re-arm gate: while waiting, consume until a physical event proves neutrality.
        if (!inputGate.onPhysicalEvent()) {
            return true
        }
        if (event == null || (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }
        ControllerInputMonitor.key(gamepadMapper, event, true)
        val padBit = gamepadMapper.digitalBinding(keyCode)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }
        gamePadState.digital[padBit.second] = gamePadState.digital[padBit.second] or padBit.first
        sendGamepadData()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (interactionLock.isLocked()) return true
        physicalTracker.onKeyEvent(keyCode, event?.action ?: KeyEvent.ACTION_UP)
        if (isFrontendHomeKey(keyCode)) {
            frontendHomeKeyGate.acceptUp()
            return true
        }
        if (isMenuOpen()) {
            if (menuInputRouter.isMenuInputKey(keyCode)) return true
            if (event != null && event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) != 0) {
                return true
            }
        }
        if (!inputGate.onPhysicalEvent()) {
            return true
        }
        if (event == null || event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) == 0) {
            return super.onKeyUp(keyCode, event)
        }
        ControllerInputMonitor.key(gamepadMapper, event ?: KeyEvent(KeyEvent.ACTION_UP, keyCode), false)
        val padBit = gamepadMapper.digitalBinding(keyCode)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }
        gamePadState.digital[padBit.second] =
            gamePadState.digital[padBit.second] and padBit.first.inv()
        sendGamepadData()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (interactionLock.isLocked()) return true
        if (event != null) physicalTracker.onMotionEvent(event)
        if (isMenuOpen()) {
            menuInputRouter.handleMotion(event)
            return true
        }
        if (!inputGate.onPhysicalEvent()) {
            return true
        }
        if (event == null || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        val mapped = gamepadMapper.motion(event)
        ControllerInputMonitor.motion(gamepadMapper, event)
        gamePadState.digital[0] = mapped.digital1
        gamePadState.digital[1] = mapped.digital2
        gamePadState.leftStickX = mapped.leftX
        gamePadState.leftStickY = mapped.leftY
        gamePadState.rightStickX = mapped.rightX
        gamePadState.rightStickY = mapped.rightY

        sendGamepadData()
        return true
    }

    private fun sendGamepadData() {
        if (isMenuOpen()) return
        if (!inputGate.onPhysicalEvent()) return
        RPCSX.instance.overlayPadData(
            gamePadState.digital[0],
            gamePadState.digital[1],
            gamePadState.leftStickX,
            gamePadState.leftStickY,
            gamePadState.rightStickX,
            gamePadState.rightStickY
        )
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        applyInsetsToPadOverlay()
    }

    private fun applyInsetsToPadOverlay() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.padOverlay) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<MarginLayoutParams> {
                leftMargin = insets.left
                rightMargin = insets.right
                topMargin = insets.top
                bottomMargin = insets.bottom
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreenImmersive()
    }

    override fun onPause() {
        super.onPause()
        // Surface loss handles pause ownership; never open a menu from here (§17).
    }

    override fun onResume() {
        super.onResume()
        gamepadMapper.reload(ControllerProfileRepository.load())
        // Menu re-arming/stale sessions are owned by the coordinator via state;
        // no blind native calls here (§17 rule 8).
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            Log.i("S3TOUCH", "root action=down x=${event.x.toInt()} y=${event.y.toInt()}")
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        const val EXTRA_RECOVERY_SAVESTATE = "recoverySavestatePath"
        const val EXTRA_RECOVERY_REQUEST_ID = "recoveryRequestId"
        const val EXTRA_USER_SAVESTATE = "userSavestatePath"
        const val EXTRA_USER_SAVESTATE_SLOT = "userSavestateSlot"
        const val EXTRA_BOOT_MODE = "bootMode"
        const val EXTRA_ORIGINAL_GAME_PATH = "originalGamePath"
        const val EXTRA_SAVESTATE_PATH = "savestatePath"
        const val EXTRA_SAVESTATE_SLOT = "savestateSlot"
        const val EXTRA_SAFE_RETRY = "safeRetry"
        private const val TITLE_ID_POLL_INTERVAL_MS = 250L
        private const val TITLE_ID_POLL_TIMEOUT_MS = 10_000L
        private const val FRAME_COPY_TIMEOUT_MS = 2_000L
        private val NEXT_ACTIVITY_ID = AtomicLong(0L)
    }
}
