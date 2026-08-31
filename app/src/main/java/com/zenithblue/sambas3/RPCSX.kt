package com.zenithblue.sambas3

import android.view.Surface
import androidx.annotation.Keep
import androidx.compose.runtime.mutableStateOf

enum class Digital1Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_SELECT(0x00000001),
    CELL_PAD_CTRL_L3(0x00000002),
    CELL_PAD_CTRL_R3(0x00000004),
    CELL_PAD_CTRL_START(0x00000008),
    CELL_PAD_CTRL_UP(0x00000010),
    CELL_PAD_CTRL_RIGHT(0x00000020),
    CELL_PAD_CTRL_DOWN(0x00000040),
    CELL_PAD_CTRL_LEFT(0x00000080),
    CELL_PAD_CTRL_PS(0x00000100),
}

enum class Digital2Flags(val bit: Int)
{
    None(0),
    CELL_PAD_CTRL_L2(0x00000001),
    CELL_PAD_CTRL_R2(0x00000002),
    CELL_PAD_CTRL_L1(0x00000004),
    CELL_PAD_CTRL_R1(0x00000008),
    CELL_PAD_CTRL_TRIANGLE(0x00000010),
    CELL_PAD_CTRL_CIRCLE(0x00000020),
    CELL_PAD_CTRL_CROSS(0x00000040),
    CELL_PAD_CTRL_SQUARE(0x00000080),
};

enum class EmulatorState {
    Stopped,
    Loading,
    Stopping,
    Running,
    Paused,
    Frozen, // paused but cannot resume
    Ready,
    Starting;

    companion object {
        fun fromInt(value: Int) = EmulatorState.entries.first { it.ordinal == value }
    }
}

enum class BootResult
{
    NoErrors,
    GenericError,
    NothingToBoot,
    WrongDiscLocation,
    InvalidFileOrFolder,
    InvalidBDvdFolder,
    InstallFailed,
    DecryptionError,
    FileCreationError,
    FirmwareMissing,
    UnsupportedDiscType,
    SavestateCorrupted,
    SavestateVersionUnsupported,
    StillRunning,
    AlreadyAdded,
    CurrentlyRestricted;

    companion object {
        fun fromInt(value: Int) = entries.first { it.ordinal == value }
    }
};

class RPCSX {
    external fun openLibrary(path: String): Boolean
    external fun getLibraryVersion(path: String): String?
    external fun initialize(rootDir: String, user: String): Boolean
    external fun installFw(fd: Int, progressId: Long): Boolean
    external fun install(fd: Int, progressId: Long): Boolean
    external fun installKey(fd: Int, requestId: Long, gamePath: String): Boolean
    external fun boot(path: String): Int
    external fun bootSavestate(savestatePath: String, originalGamePath: String): Int
    external fun clearSavestateProgress()
    external fun surfaceEvent(surface: Surface, event: Int): Boolean
    external fun surfaceEventV2(surface: Surface, event: Int, generation: Long): Boolean
    external fun usbDeviceEvent(fd: Int, vendorId: Int, productId: Int, event: Int): Boolean
    external fun processCompilationQueue(): Boolean
    external fun startMainThreadProcessor(): Boolean
    external fun overlayPadData(digital1: Int, digital2: Int, leftStickX: Int, leftStickY: Int, rightStickX: Int, rightStickY: Int): Boolean
    external fun collectGameInfo(rootDir: String, progressId: Long): Boolean
    external fun systemInfo(): String
    external fun settingsGet(path: String): String
    external fun settingsSet(path: String, value: String): Boolean
    external fun getState() : Int
    external fun kill()
    external fun resume()
    external fun loginUser(userId: String)
    external fun getUser(): String
    external fun getTitleId(): String
    external fun supportsCustomDriverLoading() : Boolean
    external fun isInstallableFile(fd: Int) : Boolean
    external fun getDirInstallPath(sfoFd: Int) : String?
    external fun getVersion(): String
    external fun setCustomDriver(path: String, libraryName: String, hookDir: String): Boolean
    external fun patchEngineVersion(): String
    external fun patchesList(): String
    external fun patchSetEnabled(hash: String, description: String, enabled: Boolean): Boolean
    // Per-title PPU manifest key (cache_abi+llvm_cpu+title_id+firmware+patches) for fingerprint.
    // Native now supports _rpcsx_getPpuManifestKeyForTitle(titleId) with global fallback.
    external fun getPpuManifestKey(titleId: String): String?
    external fun getCoreBuildId(): String?
    /** Runtime capability probes used to reject stale packaged cores. */
    external fun hasPerfMetricsExport(): Boolean
    external fun hasTrophyExports(): Boolean
    /** Optional structured performance snapshot from newer runtime cores. */
    external fun getPerfMetricsJson(): String?
    /** Enables the core-side metrics snapshot producer only while the UI monitor is active. */
    external fun setPerfMetricsEnabled(enabled: Boolean, intervalMs: Int): Boolean
    // ISO preview probe — extracts only PS3_GAME/ICON0.PNG to cache, size capped 16 MiB, no install/PPU.
    external fun extractIsoPreview(fd: Int, destinationPath: String): Int
    // Headless prelaunch runtime PPU preparation — reuses boot-discoverable PPU logic, no Surface/RSX/audio.
    external fun prepareRuntimePpu(path: String, sessionId: Long): Int
    external fun cancelRuntimePpuPreparation(sessionId: Long): Boolean

    // ── Frontend Home Menu — Kotlin owns presentation ──────────────────────
    @Keep
    fun interface FrontendEventCallback {
        fun onEvent(type: Int, payload: String?)
    }
    external fun setFrontendEventListener(callback: FrontendEventCallback?): Boolean
    external fun beginFrontendMenu(): Boolean
    external fun endFrontendMenu(resumeIfOwned: Boolean)
    external fun isFrontendMenuOpen(): Boolean
    external fun inGameMenuCapabilities(): String
    external fun requestScreenshot(): Boolean
    external fun toggleRecording(): Boolean
    external fun restartGame(): Boolean
    external fun gracefulShutdown(): Boolean
    external fun getSaveStateInfo(): String
    external fun saveState(slot: Int): Boolean
    external fun loadSaveState(slot: Int): Boolean
    external fun getCurrentTrophies(): String
    /** Reads the installed RPCS3 trophy set for a stopped title without booting it. */
    external fun getTrophiesForTitle(titleId: String): String
    external fun getFriends(): String
    external fun friendAction(action: String, username: String): Boolean
    external fun beginInGameSettingsSession(): Boolean
    external fun settingsSetTransient(path: String, value: String): Boolean
    external fun commitInGameSettingsSession(): Boolean
    external fun discardInGameSettingsSession(): Boolean
    external fun hasDirtyInGameSettings(): Boolean
    external fun endInGameSettingsSession()

    @Keep
    fun interface CompileProgressCallback {
        fun onEvent(
            domain: Int,
            phase: Int,
            origin: Int,
            jobId: Long,
            value: Long,
            max: Long,
            message: String?,
            titleId: String?,
            fileDone: Int,
            fileTotal: Int,
            moduleDone: Int,
            moduleTotal: Int
        )
    }

    external fun setCompileProgressListener(callback: CompileProgressCallback?): Boolean
    external fun supportsCompileProgressEvents(): Boolean

    companion object {
        const val COMPILE_DOMAIN_PPU = 0
        const val COMPILE_DOMAIN_SHADER = 1
        const val COMPILE_PHASE_BEGIN = 0
        const val COMPILE_PHASE_PROGRESS = 1
        const val COMPILE_PHASE_COMPLETED = 2
        const val COMPILE_PHASE_FAILED = 3
        const val COMPILE_PHASE_CANCELED = 4
        const val COMPILE_ORIGIN_INSTALL = 0
        const val COMPILE_ORIGIN_RUNTIME = 1
        const val COMPILE_ORIGIN_PRELAUNCH = 2

        const val FRONTEND_EVENT_HOME_REQUESTED = 1
        const val FRONTEND_EVENT_RECORDING_CHANGED = 2
        const val FRONTEND_EVENT_SCREENSHOT_RESULT = 3
        const val FRONTEND_EVENT_EMULATOR_ACTION_ERROR = 4
        const val FRONTEND_EVENT_SAVESTATE_COMMITTED = 5
        const val FRONTEND_EVENT_SAVESTATE_FAILED = 6
        const val FRONTEND_EVENT_RENDERER_ERROR = 7
        const val FRONTEND_EVENT_TROPHY_UNLOCKED = 8

        /**
         * JNI descriptor for [CompileProgressCallback.onEvent]. Must match
         * `kCompileProgressOnEventDescriptor` in rpcsx-android.cpp.
         * jobId, value and max are all `J` (long).
         */
        const val COMPILE_PROGRESS_ON_EVENT_JNI_DESCRIPTOR = "(IIIJJJLjava/lang/String;Ljava/lang/String;IIII)V"
        const val FRONTEND_EVENT_JNI_DESCRIPTOR = "(ILjava/lang/String;)V"

        var initialized = false
        val instance = RPCSX()
        var rootDirectory = ""
        var nativeLibDirectory = ""
        var lastPlayedGame = ""
        var activeGame = mutableStateOf<String?>(null)
        var state = mutableStateOf(EmulatorState.Stopped)
        var activeLibrary = mutableStateOf<String?>(null)

        fun boot(path: String): BootResult {
            return BootResult.fromInt(instance.boot(path))
        }

        fun updateState() {
            val newState = EmulatorState.fromInt(instance.getState())
            if (newState != state.value) {
                state.value = newState
            }
        }

        fun getState(): EmulatorState {
            updateState()
            return state.value
        }

        fun getHdd0Dir(): String {
            return rootDirectory + "config/dev_hdd0/"
        }

        fun openLibrary(): Boolean {
            val path = "$nativeLibDirectory/librpcsx-android.so"
            if (!instance.openLibrary(path)) {
                return false
            }

            activeLibrary.value = path
            runCatching {
                android.util.Log.i(
                    "S3CAP",
                    "perf_export=${if (instance.hasPerfMetricsExport()) 1 else 0} " +
                        "trophy_export=${if (instance.hasTrophyExports()) 1 else 0}"
                )
            }
            return true
        }

        init {
            try {
                System.loadLibrary("sambas3-android")
            } catch (_: UnsatisfiedLinkError) {
                // JVM unit tests run on host without native .so — allow pure-Kotlin logic tests.
            } catch (_: Throwable) {
            }
        }
    }
}
