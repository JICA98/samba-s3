package com.zenithblue.sambas3

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
import com.zenithblue.sambas3.ui.ingame.PhysicalInputTracker
import com.zenithblue.sambas3.ui.ingame.RpcsxInGameMenuCoreGateway
import com.zenithblue.sambas3.ui.ingame.RpcsxBridgeAdapter
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.utils.InputBindingPrefs
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlin.math.abs
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private var recoveryTransitionActive = false
    private var recoveryRecreateRequested = false
    // Activity.recreate() is an internal recovery handoff, not a user exit.
    // Keep the durable marker and library identity intact across that destroy.
    private var isRecoveryRecreate = false
    private var transitionBitmap: Bitmap? = null
    private val transitionController = SavestateTransitionController()
    private val bootMutex = Any()
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }

    private val physicalTracker = PhysicalInputTracker()
    private val inputGate = GameplayInputGate(physicalTracker)
    private val coreGateway: InGameMenuCoreGateway by lazy { RpcsxInGameMenuCoreGateway(RpcsxBridgeAdapter()) }
    private lateinit var coordinator: InGameMenuCoordinator
    private lateinit var menuInputRouter: InGameMenuInputRouter

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

        unregisterUsbEventListener = listenUsbEvents(this)
        enableFullScreenImmersive()

        binding.ingameOverlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.ingameOverlay.translationZ = 64f
        binding.ingameOverlay.setContent {
            RPCSXTheme {
                val uiState by coordinator.state.collectAsStateWithLifecycle()
                InGameMenuHost(
                    uiState = uiState,
                    gamePath = intent.getStringExtra("path"),
                    core = coreGateway,
                    onIntent = coordinator::dispatch
                )
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
                        backCallback.isEnabled = true
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.ExitPadMenuMode -> {
                        binding.padOverlay.setMenuMode(false)
                        backCallback.isEnabled = false
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.WaitForPhysicalNeutralThenArmGameplay -> {
                        neutralizeForwardedPad()
                        inputGate.waitForNeutral()
                        menuInputRouter.cancelRepeat()
                    }

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.ArmGameplayNow -> inputGate.arm()

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.FinishGameActivity -> {
                        thread {
                            var attempts = 0
                            while (attempts < 100) {
                                val s = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                                if (s == EmulatorState.Stopped) break
                                Thread.sleep(100)
                                attempts++
                            }
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
                                PendingSavestateRecoveryStore.markRequestFailure(this@RPCSXActivity, "transition-busy")
                                Log.e("S3SAVE", "transition controller rejected requestId=$requestId slot=${effect.slot}")
                                return@onEach
                            }
                            recoveryTransitionActive = true
                            showTransitionOverlay("Saving...")
                            captureTransitionFrame()
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

                    is com.zenithblue.sambas3.ui.ingame.InGameMenuHostEffect.SavestateTransitionFailed -> {
                        PendingSavestateRecoveryStore.markRequestFailure(this@RPCSXActivity, effect.reason)
                        failTransition(effect.reason)
                        Log.e("S3SAVE", "request failed reason=${effect.reason}")
                    }
                }
            }
            .launchIn(lifecycleScope)

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                coordinator.dispatch(InGameMenuIntent.Back)
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        debugPadReceiver = DebugPadReceiver.register(this)

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        binding.menuToggle.setOnClickListener {
            if (coordinator.state.value.isOpen) {
                coordinator.dispatch(InGameMenuIntent.Resume)
            } else if (RPCSX.getState() == EmulatorState.Running || RPCSX.getState() == EmulatorState.Paused) {
                coordinator.dispatch(InGameMenuIntent.Open)
            } else {
                Log.w("RPCSX State", "Cannot open in-game menu in state ${RPCSX.getState().name}")
            }
        }

        val pendingRecovery = PendingSavestateRecoveryStore.validForLaunch(this)
        originalGamePath = intent.getStringExtra("path")
            ?: pendingRecovery?.originalGamePath
            ?: error("RPCSXActivity requires an original game path")
        recoverySavestatePath = intent.getStringExtra(EXTRA_RECOVERY_SAVESTATE)
            ?: pendingRecovery?.takeIf { it.originalGamePath == originalGamePath }?.savestatePath
        val gamePath = originalGamePath
        RPCSX.lastPlayedGame = gamePath
        if (recoverySavestatePath != null) {
            pendingRecovery?.let {
                transitionController.beginRecoveryBoot(it.requestId, it.slot, it.savestatePath)
            }
            recoveryTransitionActive = true
            showTransitionOverlay("Restoring...")
        }

        // Frontend listener registered only after binding + content are ready (§16).
        registerFrontendListener()

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if (recoverySavestatePath == null && state == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
                    RPCSX.instance.resume()
                    return@thread
                }

                if (RPCSX.getState() != EmulatorState.Stopping && RPCSX.getState() != EmulatorState.Stopped) {
                    RPCSX.instance.kill()

                    while (RPCSX.getState() != EmulatorState.Stopped) {
                        Thread.sleep(300)
                        if (Thread.interrupted()) {
                            return@thread
                        }
                    }
                }
            }

            Log.w("RPCSX State", RPCSX.getState().name)
            var isRecoveryBoot = recoverySavestatePath != null
            if (isRecoveryBoot && !PendingSavestateRecoveryStore.markBooting(this@RPCSXActivity)) {
                Log.e("S3SAVE", "recovery boot refused after retry limit")
                PendingSavestateRecoveryStore.markFailure(this@RPCSXActivity, "retry-limit")
                runOnUiThread {
                    AlertDialogQueue.showDialog(
                        "Saved-state recovery stopped",
                        "The saved slot was kept, but automatic recovery stopped after repeated failures. " +
                            "You can load it manually from the save-state menu."
                    )
                    finish()
                }
                return@thread
            }
            val bootPath = recoverySavestatePath ?: gamePath
            val preBootTitleId = GameSettingsOverrides.resolveTitleId(gamePath, this@RPCSXActivity)
            GameSettingsOverrides.applyForGame(this@RPCSXActivity, preBootTitleId)

            Log.i("S3RENDER", "boot-savestate-begin=${isRecoveryBoot} source=$bootPath original=$gamePath")
            val bootResult = BootResult.fromInt(
                if (isRecoveryBoot) {
                    bootSavestateSerialized(bootPath, gamePath)
                } else {
                    bootSerialized(bootPath)
                }
            )
            if (bootResult != BootResult.NoErrors) {
                Log.w("S3RENDER", "boot failed source=$bootPath result=$bootResult")
                if (isRecoveryBoot) PendingSavestateRecoveryStore.markFailure(this@RPCSXActivity, bootResult.name)
                else RPCSX.activeGame.value = null
                try { RPCSX.state.value = RPCSX.getState() } catch (_: Exception) {}
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finish()
            } else {
                RPCSX.activeGame.value = gamePath
                Log.i("S3RENDER", "boot-savestate-return source=$bootPath state=${RPCSX.getState()}")
                if (isRecoveryBoot) confirmRecoveryFrameAndClear()
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

                    RPCSX.FRONTEND_EVENT_RENDERER_ERROR,
                    RPCSX.FRONTEND_EVENT_EMULATOR_ACTION_ERROR -> runOnUiThread {
                        if (recoveryTransitionActive) {
                            requestRecoveryRecreate(payload ?: "renderer-error")
                        } else {
                            Log.e("S3RENDER", "native renderer/action error payload=$payload")
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
        if (coordinator.state.value.isOpen) {
            coordinator.dispatch(InGameMenuIntent.Resume)
        } else if (RPCSX.getState() == EmulatorState.Running || RPCSX.getState() == EmulatorState.Paused) {
            coordinator.dispatch(InGameMenuIntent.Open)
        }
    }

    private fun handleSavestateCommitted(payload: String?) {
        val committed = payload ?: return
        val record = PendingSavestateRecoveryStore.commit(this, committed)
        val path = record?.savestatePath ?: run {
            Log.e("S3SAVE", "completion rejected: missing record payload=$payload")
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
        if (!transitionController.surfaceResetStarted()) {
            Log.w("S3SURFACE", "surface reset rejected requestId=${record.requestId}")
            return
        }
        binding.transitionLabel.text = "Restoring..."
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
            Log.i("S3RENDER", "first-frame-confirmed generation=${surfaceLeaseManager.currentGeneration}")
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
                        inputGate.waitForNeutral()
                        Log.i("S3RENDER", "transition-overlay-hidden")
                    }
                    .start()
            }
        }
    }

    private fun showTransitionOverlay(label: String) {
        binding.transitionLabel.text = label
        binding.transitionOverlay.alpha = 1f
        binding.transitionOverlay.visibility = View.VISIBLE
    }

    /** Capture the currently displayed game frame before the old surface is released. */
    private fun captureTransitionFrame() {
        val frame = surfaceLeaseManager.currentFrame ?: return
        requestFrameCopy(frame) { bitmap, success ->
            if (success && bitmap != null && recoveryTransitionActive) {
                transitionBitmap?.recycle()
                transitionBitmap = bitmap
                binding.transitionFrame.setImageBitmap(bitmap)
                binding.transitionFrame.visibility = View.VISIBLE
                Log.i("S3RENDER", "transition-frame-captured generation=${frame.generation} ${bitmap.width}x${bitmap.height}")
            } else {
                bitmap?.recycle()
                Log.w("S3RENDER", "transition-frame-capture-fallback generation=${frame.generation}")
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
        transitionController.fail(reason)
        transitionBitmap?.recycle()
        transitionBitmap = null
        binding.transitionFrame.setImageDrawable(null)
        binding.transitionFrame.visibility = View.GONE
        binding.transitionOverlay.visibility = View.GONE
        recoveryTransitionActive = false
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
                handleFrontendHomeRequest()
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
            Log.i("S3LIFE", "RPCSXActivity.onDestroy nativeState=$nativeState activeGame=${RPCSX.activeGame.value}")
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        physicalTracker.onKeyEvent(keyCode, event?.action ?: KeyEvent.ACTION_UP)
        if (isMenuOpen()) {
            if (event != null && keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                return handleMenuCommand(MenuCommand.HomeToggle)
            }
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
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyDown(keyCode, event)
        }
        gamePadState.digital[padBit.second] = gamePadState.digital[padBit.second] or padBit.first
        sendGamepadData()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        physicalTracker.onKeyEvent(keyCode, event?.action ?: KeyEvent.ACTION_UP)
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
        val padBit = keyCodeToPadBit(keyCode)
        if (padBit.first == 0) {
            return super.onKeyUp(keyCode, event)
        }
        gamePadState.digital[padBit.second] =
            gamePadState.digital[padBit.second] and padBit.first.inv()
        sendGamepadData()
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
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

        if (event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_L2.bit
            usesAxisL2 = true
        } else if (usesAxisL2) {
            usesAxisL2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_L2.bit.inv()
        }

        if (event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.1) {
            gamePadState.digital[1] =
                gamePadState.digital[1] or Digital2Flags.CELL_PAD_CTRL_R2.bit
            usesAxisR2 = true
        } else if (usesAxisR2) {
            usesAxisR2 = false
            gamePadState.digital[1] =
                gamePadState.digital[1] and Digital2Flags.CELL_PAD_CTRL_R2.bit.inv()
        }

        val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        gamePadState.digital[0] =
            gamePadState.digital[0] and (Digital1Flags.CELL_PAD_CTRL_LEFT.bit or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit or Digital1Flags.CELL_PAD_CTRL_UP.bit or Digital1Flags.CELL_PAD_CTRL_DOWN.bit).inv()
        if (abs(dpadX) > 0.1f) {
            if (dpadX < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_LEFT.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_RIGHT.bit
            }
        }

        if (abs(dpadY) > 0.1f) {
            if (dpadY < 0) {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_UP.bit
            } else {
                gamePadState.digital[0] =
                    gamePadState.digital[0] or Digital1Flags.CELL_PAD_CTRL_DOWN.bit
            }
        }

        gamePadState.leftStickX = (event.getAxisValue(MotionEvent.AXIS_X) * 127 + 128).toInt()
        gamePadState.leftStickY = (event.getAxisValue(MotionEvent.AXIS_Y) * 127 + 128).toInt()
        gamePadState.rightStickX = (event.getAxisValue(MotionEvent.AXIS_Z) * 127 + 128).toInt()
        gamePadState.rightStickY = (event.getAxisValue(MotionEvent.AXIS_RZ) * 127 + 128).toInt()

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
        // Menu re-arming/stale sessions are owned by the coordinator via state;
        // no blind native calls here (§17 rule 8).
    }

    companion object {
        const val EXTRA_RECOVERY_SAVESTATE = "recoverySavestatePath"
        const val EXTRA_RECOVERY_REQUEST_ID = "recoveryRequestId"
        private const val TITLE_ID_POLL_INTERVAL_MS = 250L
        private const val TITLE_ID_POLL_TIMEOUT_MS = 10_000L
        private const val FRAME_COPY_TIMEOUT_MS = 2_000L
    }
}
