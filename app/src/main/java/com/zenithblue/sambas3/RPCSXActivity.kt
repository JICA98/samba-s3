package com.zenithblue.sambas3

import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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

        val gamePath = intent.getStringExtra("path")!!
        RPCSX.lastPlayedGame = gamePath

        // Frontend listener registered only after binding + content are ready (§16).
        registerFrontendListener()

        bootThread = thread {
            if (RPCSX.getState() != EmulatorState.Stopped) {
                val state = RPCSX.getState()
                Log.w("RPCSX State", state.name)

                if (state == EmulatorState.Paused && RPCSX.activeGame.value == gamePath) {
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
            val preBootTitleId = GameSettingsOverrides.resolveTitleId(gamePath, this@RPCSXActivity)
            GameSettingsOverrides.applyForGame(this@RPCSXActivity, preBootTitleId)

            val bootResult = RPCSX.boot(gamePath)
            if (bootResult != BootResult.NoErrors) {
                Log.w("S3LIFE", "boot failed game=$gamePath result=$bootResult clearing activeGame")
                RPCSX.activeGame.value = null
                try { RPCSX.state.value = RPCSX.getState() } catch (_: Exception) {}
                AlertDialogQueue.showDialog(
                    getString(R.string.failed_to_boot),
                    getString(R.string.error_with_msg, bootResult.name)
                )
                finish()
            } else {
                RPCSX.activeGame.value = gamePath
                Log.i("S3LIFE", "boot success game=$gamePath activeGame set, state=${RPCSX.getState()}")
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
            if (nativeState == EmulatorState.Stopped) {
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
        private const val TITLE_ID_POLL_INTERVAL_MS = 250L
        private const val TITLE_ID_POLL_TIMEOUT_MS = 10_000L
    }
}
