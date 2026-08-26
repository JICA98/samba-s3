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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import com.zenithblue.sambas3.databinding.ActivityRpcs3Binding
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import com.zenithblue.sambas3.overlay.State
import com.zenithblue.sambas3.ui.ingame.EmulationOverlayHost
import com.zenithblue.sambas3.ui.ingame.InGamePage
import com.zenithblue.sambas3.ui.ingame.InGameUiState
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.utils.InputBindingPrefs
import kotlin.concurrent.thread
import kotlin.math.abs

class RPCSXActivity : ComponentActivity() {
    private lateinit var binding: ActivityRpcs3Binding
    private lateinit var unregisterUsbEventListener: () -> Unit
    private var gamePadState: State = State()
    private var usesAxisL2 = false
    private var usesAxisR2 = false
    private var bootThread: Thread? = null
    private var debugPadReceiver: DebugPadReceiver? = null
    private val inputBindings by lazy { InputBindingPrefs.loadBindings() }

    // In-game overlay state machine (Compose host; P2). The ComposeView content is
    // set ONCE here and reacts to [inGameUi] changes.
    private val inGameUi = InGameUiState()

    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialogQueue.hostsSuppressed = true
        // Ensure per-process singletons are ready even when RPCSXActivity is the cold entry point (adb launch after force-stop).
        try { com.zenithblue.sambas3.utils.GeneralSettings.init(this) } catch (_: Exception) {}
        if (RPCSX.rootDirectory.isEmpty()) {
            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null)?.toString()?.let { if (it.endsWith("/")) it else "$it/" } ?: ""
            try { com.zenithblue.sambas3.utils.FileUtil.fixNestedGameDirs(RPCSX.rootDirectory) } catch (_: Exception) {}
        }
        // Cold-start library init fallback (skill sambas3-game-launch §2): direct RPCSXActivity after force-stop bypasses MainActivity.kt:28-86.
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
        // Register compile progress bridge early (idempotent, process singleton). Promotes FGS only on first real event.
        try {
            NotificationChannels.ensureCreated(this)
            CompileProgressBridge.registerOnce(this)
        } catch (e: Exception) {
            Log.w("RPCSX-UI", "CompileProgressBridge register failed: ${e.message}")
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
                    uiState = inGameUi,
                    gamePath = intent.getStringExtra("path"),
                    onCloseRequest = ::closeInGamePages,
                    onOpenCoreHomeMenu = ::openCoreHomeMenu,
                    onExitConfirmed = ::exitGame
                )
            }
        }

        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (!inGameUi.popSubPage()) closeInGamePages()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        debugPadReceiver = DebugPadReceiver.register(this)

        binding.oscToggle.setOnClickListener {
            binding.padOverlay.isInvisible = !binding.padOverlay.isInvisible
            binding.oscToggle.setImageResource(if (binding.padOverlay.isInvisible) R.drawable.ic_osc_off else R.drawable.ic_show_osc)
        }

        binding.menuToggle.setOnClickListener {
            if (RPCSX.getState() != EmulatorState.Running) {
                Log.w("RPCSX State", "Cannot open home menu in state ${RPCSX.getState().name}")
                return@setOnClickListener
            }
            if (inGameUi.page.value == InGamePage.Closed) {
                openInGamePage(InGamePage.Menu)
            } else {
                closeInGamePages()
            }
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
            // activeGame ownership: only set AFTER successful boot that will own rendering.
            // Do NOT claim ownership before boot, otherwise compile-only handoff leaves stale activeGame.

            // Pre-boot override replay (P4): full ladder defaults -> baseline ->
            // global -> per-title, executed while Emu.IsStopped() so restart-required
            // cfg nodes accept their values. Runs on THIS bootThread (single serial
            // g_cfg writer).
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
                // Successful boot now owns rendering — publish ownership after boot.
                RPCSX.activeGame.value = gamePath
                Log.i("S3LIFE", "boot success game=$gamePath activeGame set, state=${RPCSX.getState()}")
                pollAndLearnTitleId(gamePath)
            }
        }
    }

    // ── In-game Compose overlay pages ──────────────────────────────────────

    private fun openInGamePage(page: InGamePage) {
        inGameUi.open(page)
        binding.ingameOverlay.visibility = View.VISIBLE
        binding.padOverlay.setMenuMode(true)
        backCallback.isEnabled = true
    }

    private fun closeInGamePages() {
        inGameUi.close()
        binding.ingameOverlay.visibility = View.GONE
        binding.padOverlay.setMenuMode(false)
        backCallback.isEnabled = false
    }

    /** Engine draws its own native home menu (guarded to Running). */
    private fun openCoreHomeMenu() {
        if (RPCSX.getState() != EmulatorState.Running) return
        RPCSX.instance.openHomeMenu()
    }

    /** Exit Game confirm -> graceful kill, then finish back to the launcher. */
    private fun exitGame() {
        thread {
            try {
                RPCSX.instance.kill()
                // Wait for real native Stopped before publishing, off Main (BUG G)
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
                    Log.i("S3LIFE", "exitGame reached Stopped, cleared activeGame")
                } else {
                    Log.w("S3LIFE", "exitGame timeout state=$finalState")
                }
            } catch (e: Exception) {
                Log.w("RPCSX State", "kill failed: ${e.message}")
            }
            runOnUiThread { finish() }
        }
    }

    /**
     * Post-boot learning (P4 step 5, review F5): poll getTitleId up to 10 s ON the
     * bootThread; on first non-blank value persist the path->titleId learning entry
     * and replay ONLY the per-title tier — never defaults/baseline/global while
     * Running. When the path was already TITLE_ID-shaped the pre-boot ladder applied
     * this tier too, so re-application is benign documented rejection noise for
     * restart-required nodes.
     */
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
        // BUG A fix: never fabricate Paused just because Activity died. Native is source of truth.
        try {
            val nativeState = RPCSX.getState()
            Log.i("S3LIFE", "RPCSXActivity.onDestroy nativeState=$nativeState activeGame=${RPCSX.activeGame.value}")
            RPCSX.state.value = nativeState
            // Clear stale compile-only ownership: if native is Stopped, this activity never owned gameplay.
            if (nativeState == EmulatorState.Stopped) {
                val myPath = try { intent.getStringExtra("path") } catch (_: Exception) { null }
                if (myPath != null && RPCSX.activeGame.value == myPath) {
                    // If we are Stopped, check whether gameplay ever reached Running — if not, this was compile-only handoff.
                    // Safer: if native Stopped, clear ownership so Home does not think gameplay owns engine.
                    // Genuine paused game would have nativeState == Paused, not Stopped, so not cleared here.
                    Log.i("S3LIFE", "onDestroy Stopped clearing stale activeGame=$myPath")
                    RPCSX.activeGame.value = null
                } else if (RPCSX.activeGame.value != null && RPCSX.activeGame.value != myPath) {
                    // Another game owns it — leave alone
                } else if (myPath == null && RPCSX.activeGame.value != null) {
                    // Defensive: no path extra but Stopped — clear to avoid stale STOP button
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
        // Ensure we don't leave thread dangling beyond 2s; if still alive, let it timeout.
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
            // I don't think we need `displayCutout` insets here as well
            // Since there is hardly any overlay overlapping with it
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

    companion object {
        private const val TITLE_ID_POLL_INTERVAL_MS = 250L
        private const val TITLE_ID_POLL_TIMEOUT_MS = 10_000L
    }
}
