package com.zenithblue.sambas3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.zenithblue.sambas3.debug.DebugPadReceiver
import com.zenithblue.sambas3.dialogs.AlertDialogQueue
import com.zenithblue.sambas3.ui.navigation.AppNavHost
import com.zenithblue.sambas3.crash.CrashEvidenceCollector
import com.zenithblue.sambas3.session.EmulationSessionJournal
import com.zenithblue.sambas3.ui.crash.GameCrashScreen
import com.zenithblue.sambas3.ui.games.launch.GameSavestateRepository
import com.zenithblue.sambas3.utils.GeneralSettings
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuDriverSelection
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var unregisterUsbEventListener: () -> Unit = {}
    private var debugPadReceiver: DebugPadReceiver? = null
    private var handingOffToRecovery = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GeneralSettings.init(this)
        LogMonitor.start(this) // capture logs from boot

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

            lifecycleScope.launch {
                GameRepository.load()
            }

            FirmwareRepository.load()

            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

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

        // A save can be committed after the renderer/activity is interrupted.
        // Resume the exact durable slot before showing the library, while
        // keeping the original game path as the activity/library identity.
        PendingSavestateRecoveryStore.validForLaunch(this)?.let { pending ->
            if (pending.originalGamePath.isNotBlank()) {
                handingOffToRecovery = true
                startActivity(Intent(this, RPCSXActivity::class.java).apply {
                    putExtra("path", pending.originalGamePath)
                    putExtra(RPCSXActivity.EXTRA_RECOVERY_SAVESTATE, pending.savestatePath)
                    putExtra(RPCSXActivity.EXTRA_RECOVERY_REQUEST_ID, pending.requestId)
                })
                finish()
                return
            }
        }

        PendingSavestateRecoveryStore.exhausted(this)?.let { failed ->
            AlertDialogQueue.showDialog(
                "Saved-state recovery stopped",
                "The saved slot was kept, but automatic recovery stopped after repeated failures. " +
                    "You can load slot ${failed.slot} manually."
            )
        }

        val unfinishedSession = EmulationSessionJournal.unfinished(this)
        val previousReport = unfinishedSession?.let { CrashEvidenceCollector.collect(this, it) }
        setContent {
            RPCSXTheme {
                var showPreviousFailure by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(previousReport != null) }
                if (showPreviousFailure && previousReport != null && unfinishedSession != null) {
                    GameCrashScreen(
                        report = previousReport,
                        onRetry = {
                            EmulationSessionJournal.clear(this@MainActivity)
                            startActivity(Intent(this@MainActivity, RPCSXActivity::class.java).putExtra("path", unfinishedSession.gamePath))
                            handingOffToRecovery = true
                            finish()
                        },
                        onSafeRetry = {
                            EmulationSessionJournal.clear(this@MainActivity)
                            startActivity(Intent(this@MainActivity, RPCSXActivity::class.java).apply {
                                putExtra("path", unfinishedSession.gamePath)
                                putExtra(RPCSXActivity.EXTRA_SAFE_RETRY, true)
                            })
                            handingOffToRecovery = true
                            finish()
                        },
                        onContinue = {
                            val game = com.zenithblue.sambas3.GameRepository.list().firstOrNull { it.info.path == unfinishedSession.gamePath }
                            val slot = game?.let { GameSavestateRepository.slots(this@MainActivity, it).filter { s -> s.exists }.maxByOrNull { s -> s.mtimeMs } }
                            if (slot?.path != null) {
                                EmulationSessionJournal.clear(this@MainActivity)
                                startActivity(Intent(this@MainActivity, RPCSXActivity::class.java).apply {
                                    putExtra("path", unfinishedSession.gamePath)
                                    putExtra(RPCSXActivity.EXTRA_USER_SAVESTATE, slot.path)
                                    putExtra(RPCSXActivity.EXTRA_USER_SAVESTATE_SLOT, slot.slot)
                                })
                                handingOffToRecovery = true
                                finish()
                            }
                        },
                        onChooseSave = { showPreviousFailure = false },
                        onConfigure = { showPreviousFailure = false },
                        onExit = { EmulationSessionJournal.clear(this@MainActivity); showPreviousFailure = false }
                    )
                } else {
                    AppNavHost()
                }
            }
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
        debugPadReceiver = DebugPadReceiver.register(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
        try { debugPadReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        if (!handingOffToRecovery) LogMonitor.stop()
    }
}
