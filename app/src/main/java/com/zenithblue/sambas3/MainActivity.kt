package com.zenithblue.sambas3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.ui.navigation.AppNavHost
import com.zenithblue.sambas3.input.ControllerInputMonitor
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverSelection
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var unregisterUsbEventListener: () -> Unit = {}
    private var debugPadReceiver: DebugPadReceiver? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Legacy filesystem imports need the runtime read grant on Android 10–12.
        // Android 13+ ignores READ_EXTERNAL_STORAGE; SAF grants the selected URI.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S) {
            Permission.ExternalStorageRead.requestPermission(this)
        }

        GeneralSettings.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ensure notification channel exists for all entry points (cold RPCSXActivity safety)
        try { NotificationChannels.ensureCreated(this) } catch (_: Exception) {
            // Fallback to original inline creation if helper fails
            with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
                val channel = NotificationChannel(
                    "rpcsx-progress",
                    getString(R.string.installation_progress),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                createNotificationChannel(channel)
            }
        }

        if (!RPCSX.initialized) {
            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null).toString()
            if (!RPCSX.rootDirectory.endsWith("/")) {
                RPCSX.rootDirectory += "/"
            }

            // Fix historical nested path bug before loading games.json
            try { com.zenithblue.sambas3.utils.FileUtil.fixNestedGameDirs(RPCSX.rootDirectory) } catch (_: Exception) {}

            // Restore persisted ISO candidates (folder scan) without creating fake Games.
            try { com.zenithblue.sambas3.utils.LibraryCandidatesRepository.load(this@MainActivity) } catch (_: Exception) {}
            try { com.zenithblue.sambas3.utils.ScannedFoldersRepository.load(this@MainActivity) } catch (_: Exception) {}

            lifecycleScope.launch {
                GameRepository.load()
                // Direct ISO entries from older builds manufactured Ready.
                // Reset those lies when no PPU cache objects exist so Home
                // and Launch Center show PREPARE PPU instead of fake done.
                com.zenithblue.sambas3.iso.DirectIsoManager.reconcileLaunchReadiness(
                    this@MainActivity
                )
            }

            FirmwareRepository.load()

            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

            try {
                com.zenithblue.sambas3.logging.LogBroker.ensureStarted(this@MainActivity)
            } catch (e: Exception) {
                android.util.Log.w("Main", "LogBroker pre-init start failed: ${e.message}")
            }

            RPCSX.openLibrary()
            // S3CORE build ID — must log after dlopen so stale cores are immediately visible in logcat
            try {
                val coreId = RPCSX.instance.getCoreBuildId() ?: "unknown"
                android.util.Log.i("S3CORE", "core_build_id=$coreId")
                android.util.Log.i("S3LIFE", "core_build_id=$coreId session=${com.zenithblue.sambas3.utils.Telemetry.sessionId}")
                // Expose patch SHA for quick grep
                if (coreId.contains("patch_sha256=")) {
                    android.util.Log.i("S3CORE", "patch_sha256=${coreId.substringAfter("patch_sha256=").substringBefore(" ").take(16)}")
                }
            } catch (_: Exception) {}

            if (RPCSX.activeLibrary.value != null) {
                RPCSX.instance.initialize(RPCSX.rootDirectory, UserRepository.getUserFromSettings())

                // Sync Play-bundled Turnip packages off the UI thread, then apply selection.
                lifecycleScope.launch {
                    GpuDriverHelper.syncBundledDrivers(this@MainActivity)
                    GpuDriverHelper.ensureValidSelection(this@MainActivity)
                    GpuDriverSelection.applyStoredSelection(this@MainActivity, nativeLibraryDir)
                }

                lifecycleScope.launch {
                    UserRepository.load()
                }

                RPCSX.initialized = true

                // Register compile progress bridge early (idempotent). FGS promotes only on first real event.
                try { CompileProgressBridge.registerOnce(this@MainActivity) } catch (e: Exception) { android.util.Log.w("Main", "CompileProgressBridge register failed: ${e.message}") }

                thread {
                    RPCSX.instance.startMainThreadProcessor()
                }

                thread {
                    RPCSX.instance.processCompilationQueue()
                }
            } else {
                // Even if already initialized (e.g., process recreation), ensure bridge registered
                try { CompileProgressBridge.registerOnce(this@MainActivity) } catch (_: Exception) {}
            }
        } else {
            // Already initialized path — ensure bridge registered without re-init
            try { NotificationChannels.ensureCreated(this) } catch (_: Exception) {}
            try { CompileProgressBridge.registerOnce(this) } catch (_: Exception) {}
        }

        val coldStart = savedInstanceState == null
        // A process relaunched after an emulator/app crash restores this Activity
        // with saved state and/or an unfinished session. Showing the boot splash
        // there makes a recovery restart look like a fresh launch, so only true
        // cold starts get it.
        val pendingRecovery = runCatching {
            com.zenithblue.sambas3.crash.HomeRecoveryRepository.hasPendingRecovery(this)
        }.getOrDefault(false)
        val showBootSplash = coldStart && !pendingRecovery
        android.util.Log.i(
            "S3SPLASH",
            "coldStart=$coldStart restored=${savedInstanceState != null} " +
                "pendingRecovery=$pendingRecovery show=$showBootSplash"
        )

        setContent {
            RPCSXTheme {
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(initialRoute = intent.getStringExtra("route"))
                    if (showBootSplash) {
                        com.zenithblue.sambas3.ui.splash.SplashOverlay(Modifier.fillMaxSize())
                    }
                }
            }
        }

        try {
            com.zenithblue.sambas3.ppu.ImportPpuPreparationCoordinator.reconcileInterruptedState(this)
        } catch (e: Exception) {
            android.util.Log.w("Main", "PPU interrupted-state recovery failed: ${e.message}")
        }

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { com.zenithblue.sambas3.logging.LogBroker.ensureStarted(this@MainActivity) } catch (e: Exception) {
                android.util.Log.w("Main", "LogBroker start failed: ${e.message}")
            }
        }

        // Never gate Home on diagnostics. Recovery analysis is deliberately
        // started after AppNavHost has entered composition.
        lifecycleScope.launch {
            com.zenithblue.sambas3.crash.HomeRecoveryRepository.refresh(this@MainActivity)
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (debugPadReceiver == null) debugPadReceiver = DebugPadReceiver.register(this)
    }

    override fun onPause() {
        try { debugPadReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        debugPadReceiver = null
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
        try { debugPadReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        debugPadReceiver = null
        try { LogMonitor.flushWriters() } catch (_: Exception) {}
    }

    @android.annotation.SuppressLint("RestrictedApi") // Activity override must delegate unhandled input.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val physicalSource = event.source and (
            android.view.InputDevice.SOURCE_KEYBOARD or
                android.view.InputDevice.SOURCE_GAMEPAD or
                android.view.InputDevice.SOURCE_JOYSTICK or
                android.view.InputDevice.SOURCE_DPAD
            ) != 0
        if (physicalSource && ControllerInputMonitor.consumesPhysicalInput()) {
            ControllerInputMonitor.observeKey(event, event.action == android.view.KeyEvent.ACTION_DOWN)
            return true
        }
        if (physicalSource) {
            ControllerInputMonitor.observeKey(event, event.action == android.view.KeyEvent.ACTION_DOWN)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        val physicalSource = event.source and (
            android.view.InputDevice.SOURCE_GAMEPAD or
                android.view.InputDevice.SOURCE_JOYSTICK
            ) != 0
        if (physicalSource && ControllerInputMonitor.consumesPhysicalInput()) {
            ControllerInputMonitor.observeMotion(event)
            return true
        }
        if (physicalSource) ControllerInputMonitor.observeMotion(event)
        return super.dispatchGenericMotionEvent(event)
    }
}
