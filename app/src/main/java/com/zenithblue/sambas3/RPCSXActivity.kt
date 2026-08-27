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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.zenithblue.sambas3.databinding.ActivityRpcs3Binding
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.overlay.State
import com.zenithblue.sambas3.ui.ingame.EmulationOverlayHost
import com.zenithblue.sambas3.ui.ingame.InGameMenuController
import com.zenithblue.sambas3.ui.ingame.InGameMenuInputRouter
import com.zenithblue.sambas3.ui.ingame.MenuInput
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.utils.InputBindingPrefs
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private var debugPadReceiver: DebugPadReceiver? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }

    private val inGameMenuController = InGameMenuController()
    private lateinit var menuInputRouter: InGameMenuInputRouter
    private var gameplayInputArmed = true

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

        // Frontend Home Menu ownership: register listener to route PS/Home to Kotlin
        try {
            RPCSX.instance.setFrontendEventListener { type, _ ->
                if (type == RPCSX.FRONTEND_EVENT_HOME_REQUESTED) {
                    runOnUiThread {
                        handleFrontendHomeRequest()
                    }
                }
            }
            Log.i("RPCSX-UI", "FrontendEventListener registered")
        } catch (e: Exception) {
            Log.w("RPCSX-UI", "FrontendEventListener failed ${e.message}")
        }

        menuInputRouter = InGameMenuInputRouter(inGameMenuController) { input ->
            handleMenuInput(input)
        }

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
                EmulationOverlayHost(
                    controller = inGameMenuController,
                    gamePath = intent.getStringExtra("path"),
                    onExitConfirmed = ::exitGame,
                    onRequestScreenshot = { requestScreenshot() },
                    onToggleRecording = { toggleRecording() },
                    onSaveState = { slot -> saveState(slot) },
                    onLoadState = { slot -> loadState(slot) }
                )
            }
        }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (inGameMenuController.isOpen) {
                    // Let controller decide: if on Settings dirty, it will signal via back() returning false -> keep open, UI will show dialog
                    val consumed = inGameMenuController.back()
                    if (!consumed) {
                        // dirty dialog is shown inside Settings page; keep overlay visible
                    } else if (!inGameMenuController.isOpen) {
                        closeInGameMenu(resume = true)
                    }
                } else {
                    // No menu open, let system handle (or open menu?)
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        debugPadReceiver = DebugPadReceiver.register(this)

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        binding.menuToggle.setOnClickListener {
            if (inGameMenuController.isOpen) {
                inGameMenuController.resume()
                closeInGameMenu(resume = true)
                return@setOnClickListener
            }
            if (RPCSX.getState() != EmulatorState.Running) {
                Log.w("RPCSX State", "Cannot open home menu in state ${RPCSX.getState().name}")
                return@setOnClickListener
            }
            openInGameMenu()
        }

        val gamePath = intent.getStringExtra("path")!!
        RPCSX.lastPlayedGame = gamePath

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

    // ── In-game Compose overlay pages ──────────────────────────────────────

    private fun openInGameMenu() {
        // Order per plan Phase 15: verify Running, neutralize, beginFrontendMenu, capabilities, enter overlay mode
        if (RPCSX.getState() != EmulatorState.Running && RPCSX.getState() != EmulatorState.Paused) {
            Log.w("InGameMenu", "openInGameMenu rejected state=${RPCSX.getState()}")
            return
        }
        neutralizePhysicalPad()
        binding.padOverlay.cancelActiveInputsAndNeutralize()
        lifecycleScope.launch {
            try {
                inGameMenuController.openMain()
                binding.ingameOverlay.visibility = View.VISIBLE
                binding.padOverlay.setMenuMode(true)
                backCallback.isEnabled = true
                gameplayInputArmed = false
            } catch (e: Exception) {
                Log.w("InGameMenu", "openMain failed ${e.message}")
            }
        }
    }

    private fun closeInGameMenu(resume: Boolean) {
        binding.ingameOverlay.visibility = View.GONE
        binding.padOverlay.setMenuMode(false)
        backCallback.isEnabled = inGameMenuController.isOpen
        if (resume) {
            // Wait for neutral before re-arming
            gameplayInputArmed = false
        } else {
            gameplayInputArmed = false
        }
        // If controller still open (e.g. back from subpage), keep overlay visible
        if (inGameMenuController.isOpen) {
            binding.ingameOverlay.visibility = View.VISIBLE
            binding.padOverlay.setMenuMode(true)
            backCallback.isEnabled = true
        }
    }

    private fun handleFrontendHomeRequest() {
        if (inGameMenuController.isOpen) {
            inGameMenuController.resume()
            closeInGameMenu(resume = true)
        } else {
            if (RPCSX.getState() == EmulatorState.Running || RPCSX.getState() == EmulatorState.Paused) {
                openInGameMenu()
            }
        }
    }

    private fun handleMenuInput(input: MenuInput): Boolean {
        // Route to controller navigation or page-specific handlers
        when (input) {
            is MenuInput.Back -> {
                val wasOpen = inGameMenuController.isOpen
                val consumed = inGameMenuController.back()
                if (!consumed) {
                    // Dirty settings: stay open, dialog will show
                    return true
                }
                if (!inGameMenuController.isOpen && wasOpen) {
                    closeInGameMenu(resume = true)
                }
                return true
            }
            is MenuInput.Home -> {
                handleFrontendHomeRequest()
                return true
            }
            is MenuInput.Up -> {
                // Let LazyColumn handle? Controller already moves selection via router default
                return true
            }
            is MenuInput.Down -> return true
            else -> return true
        }
    }

    private fun neutralizePhysicalPad() {
        gamePadState = State()
        usesAxisL2 = false
        usesAxisR2 = false
        try {
            RPCSX.instance.overlayPadData(0, 0, 127, 127, 127, 127)
        } catch (_: Exception) {}
    }

    private fun requestScreenshot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try { RPCSX.instance.requestScreenshot() } catch (e: Exception) { Log.w("InGameMenu", "screenshot failed ${e.message}") }
        }
    }

    private fun toggleRecording() {
        lifecycleScope.launch(Dispatchers.IO) {
            try { RPCSX.instance.toggleRecording() } catch (e: Exception) { Log.w("InGameMenu", "recording failed ${e.message}") }
        }
    }

    private fun saveState(slot: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ok = RPCSX.instance.saveState(slot)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        // For suspend mode, activity will finish after kill; for normal, resume is handled by restart
                        if (inGameMenuController.capabilities.savestate?.suspendMode == true) {
                            inGameMenuController.closeWithoutResume()
                            closeInGameMenu(resume = false)
                            // Wait for kill then finish
                            thread {
                                var attempts = 0
                                while (attempts < 50) {
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
            } catch (e: Exception) { Log.w("InGameMenu", "saveState failed ${e.message}") }
        }
    }

    private fun loadState(slot: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ok = RPCSX.instance.loadSaveState(slot)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        inGameMenuController.closeWithoutResume()
                        closeInGameMenu(resume = false)
                    }
                }
            } catch (e: Exception) { Log.w("InGameMenu", "loadState failed ${e.message}") }
        }
    }

    /** Exit Game confirm -> graceful shutdown, then finish back to the launcher. */
    private fun exitGame() {
        thread {
            try {
                val ok = try { RPCSX.instance.gracefulShutdown() } catch (_: Exception) { false }
                if (!ok) {
                    // Fallback kill if graceful not available (old core)
                    try { RPCSX.instance.kill() } catch (_: Exception) {}
                }
                var attempts = 0
                while (attempts < 50) {
                    val s = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                    if (s == EmulatorState.Stopped) break
                    Thread.sleep(100)
                    attempts++
                }
                val finalState = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                RPCSX.state.value = finalState
                if (finalState == EmulatorState.Stopped) {
                    RPCSX.activeGame.value = null
                    Log.i("S3LIFE", "exitGame graceful reached Stopped, cleared activeGame")
                } else {
                    Log.w("S3LIFE", "exitGame timeout state=$finalState")
                    // Fallback kill
                    try { RPCSX.instance.kill() } catch (_: Exception) {}
                    Thread.sleep(500)
                    RPCSX.state.value = try { RPCSX.getState() } catch (_: Exception) { EmulatorState.Stopped }
                    if (RPCSX.state.value == EmulatorState.Stopped) RPCSX.activeGame.value = null
                }
                try { RPCSX.instance.setFrontendEventListener(null) } catch (_: Exception) {}
                try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w("RPCSX State", "gracefulShutdown failed: ${e.message}")
                try { RPCSX.instance.kill() } catch (_: Exception) {}
            }
            runOnUiThread { finish() }
        }
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
        try { RPCSX.instance.setFrontendEventListener(null) } catch (_: Exception) {}
        try { if (inGameMenuController.isOpen) RPCSX.instance.endFrontendMenu(false) } catch (_: Exception) {}
        try { RPCSX.instance.endInGameSettingsSession() } catch (_: Exception) {}
        try {
            val nativeState = RPCSX.getState()
            Log.i("S3LIFE", "RPCSXActivity.onDestroy nativeState=$nativeState activeGame=${RPCSX.activeGame.value}")
            RPCSX.state.value = nativeState
            if (nativeState == EmulatorState.Stopped) {
                val myPath = try { intent.getStringExtra("path") } catch (_: Exception) { null }
                if (myPath != null && RPCSX.activeGame.value == myPath) {
                    Log.i("S3LIFE", "onDestroy Stopped clearing stale activeGame=$myPath")
                    RPCSX.activeGame.value = null
                } else if (RPCSX.activeGame.value != null && RPCSX.activeGame.value != myPath) {
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

    private fun isGameplayInputNeutral(): Boolean {
        return gamePadState.digital[0] == 0 && gamePadState.digital[1] == 0 &&
               abs(gamePadState.leftStickX - 127) < 20 && abs(gamePadState.leftStickY - 127) < 20 &&
               abs(gamePadState.rightStickX - 127) < 20 && abs(gamePadState.rightStickY - 127) < 20
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (inGameMenuController.isOpen) {
            // Route exclusively to menu
            if (menuInputRouter.handleKeyDown(keyCode, event)) return true
            // Also handle confirm/back that controller may not have handled via router default?
            // Ensure menu consumes all gamepad/dpad keys while open
            if (event != null && (event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)) != 0) {
                return true
            }
        }
        // Gameplay re-arm gate: if not armed, only allow neutral transition to re-arm, otherwise drop
        if (!gameplayInputArmed) {
            if (isGameplayInputNeutral()) gameplayInputArmed = true else {
                // Still waiting for neutral; drop this input but still neutralize to avoid stuck
                // Don't send to core
                return true
            }
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
        if (inGameMenuController.isOpen) {
            menuInputRouter.handleKeyUp(keyCode, event)
            if (event != null && event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD) != 0) {
                return true
            }
        }
        if (!gameplayInputArmed) {
            // Allow re-arm check on key up
            if (isGameplayInputNeutral()) gameplayInputArmed = true
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
        // Check if now neutral, re-arm
        if (!gameplayInputArmed && isGameplayInputNeutral()) gameplayInputArmed = true
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (inGameMenuController.isOpen) {
            if (menuInputRouter.handleGenericMotion(event)) return true
            // Consume all joystick motion while menu open, send neutral only
            return true
        }
        if (!gameplayInputArmed) {
            if (isGameplayInputNeutral()) gameplayInputArmed = true else return true
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
        // Only gameplay mode calls this; menu mode sends neutral separately
        if (inGameMenuController.isOpen) return
        if (!gameplayInputArmed) return
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
        // Surface loss will handle pause ownership; do not open native menu
    }

    override fun onResume() {
        super.onResume()
        // Re-query frontend menu state if activity recreated while menu open
        try {
            val isOpen = RPCSX.instance.isFrontendMenuOpen()
            if (isOpen && !inGameMenuController.isOpen) {
                // Stale session: end without resume to clear backend state
                RPCSX.instance.endFrontendMenu(false)
                Log.w("InGameMenu", "onResume cleared stale frontend menu session")
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val TITLE_ID_POLL_INTERVAL_MS = 250L
        private const val TITLE_ID_POLL_TIMEOUT_MS = 10_000L
    }
}
