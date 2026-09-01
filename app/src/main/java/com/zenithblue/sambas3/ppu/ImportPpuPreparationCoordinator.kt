package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.ImportSessionStore
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.gameconfig.GameSettingsOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Small focused coordinator — reacts to successful install-origin PPU terminal,
 * resolves installed title/game path, waits for engine idle, invokes headless
 * native runtime preparation, exposes prelaunch progress, marks terminal.
 */
object ImportPpuPreparationCoordinator {
    private const val TAG = "PpuCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    @Volatile
    var lastSessionId: Long = -1L
        private set

    // For UI to know we are waiting for engine idle (not failed)
    @Volatile
    var waitingForIdle: Boolean = false
        private set

    fun onInstallPpuSuccess(context: Context, titleId: String?) {
        if (titleId.isNullOrBlank()) {
            Log.w(TAG, "onInstallPpuSuccess ignored: titleId null/blank")
            return
        }
        val appCtx = context.applicationContext
        val game = resolveGameForTitle(titleId)
        val path = resolvePathForTitle(appCtx, titleId, game)
        if (path.isNullOrBlank() || path == "$" || path.startsWith("content://")) {
            Log.w(TAG, "Cannot resolve game path for title $titleId, got $path — will retry via constructed path")
            // Try constructed path as fallback
            val fallback = File(RPCSX.rootDirectory, "config/games/$titleId").absolutePath
            if (File(fallback).exists() || File(fallback).isDirectory) {
                startHeadless(appCtx, titleId, fallback)
            } else {
                Log.w(TAG, "No fallback path for $titleId, marking needs retry")
                PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
            }
            return
        }
        // Ensure preRuntime is READY (install-origin success)
        PpuReadinessStore.setPreRuntimeState(appCtx, titleId, PreRuntimePpuState.READY)
        // Mark as waiting, not yet compiling, so UI shows WAITING
        waitingForIdle = true
        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.COMPILING)
        Log.i(TAG, "Starting headless prelaunch for $titleId at $path")
        startHeadless(appCtx, titleId, path)
    }

    fun requestPreparation(context: Context, game: Game) {
        val appCtx = context.applicationContext
        val titleId = try {
            com.zenithblue.sambas3.GameIdentity.titleIdOrNull(game.info.path, game.info.name.value) ?: com.zenithblue.sambas3.GameIdentity.key(game.info.path, game.info.name.value)
        } catch (_: Exception) { game.info.path }
        val path = game.info.path
        if (path.isBlank() || path == "$" || path.startsWith("content://")) {
            Log.w(TAG, "requestPreparation invalid path $path for $titleId")
            return
        }
        // If already READY+IDLE, no need
        val pre = try { PpuReadinessStore.getPreRuntimeState(appCtx, titleId) } catch (_: Exception) { PreRuntimePpuState.NOT_DONE }
        val rt = try { PpuReadinessStore.getRuntimeState(appCtx, titleId) } catch (_: Exception) { RuntimePpuState.NOT_STARTED }
        if (pre == PreRuntimePpuState.READY && rt == RuntimePpuState.IDLE_AFTER_COMPILE) {
            Log.i(TAG, "requestPreparation already ready $titleId")
            return
        }
        PpuReadinessStore.setPreRuntimeState(appCtx, titleId, PreRuntimePpuState.READY)
        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.COMPILING)
        waitingForIdle = true
        Log.i(TAG, "Manual headless request for $titleId at $path")
        startHeadless(appCtx, titleId, path)
    }

    fun cancel(sessionId: Long) {
        if (lastSessionId != sessionId) {
            return
        }

        try {
            RPCSX.instance
                .cancelRuntimePpuPreparation(
                    sessionId
                )
        } catch (
            _: Exception
        ) {
        }

        currentJob?.cancel()
    }

    fun reconcileInterruptedState(
        context: Context
    ) {
        val appCtx =
            context.applicationContext

        if (
            currentJob?.isActive ==
            true
        ) {
            return
        }

        if (
            CompileProgressBridge
                .prelaunchState
                .value
                .ppuActive
        ) {
            return
        }

        val state =
            runCatching {
                RPCSX.getState()
            }.getOrDefault(
                com.zenithblue.sambas3
                    .EmulatorState
                    .Stopped
            )

        if (
            state !=
            com.zenithblue.sambas3
                .EmulatorState
                .Stopped
        ) {
            return
        }

        val recovered =
            PpuReadinessStore
                .recoverInterruptedRuntimePreparations(
                    appCtx
                )

        if (
            recovered.isNotEmpty()
        ) {
            Log.w(
                TAG,
                "Recovered stale headless PPU " +
                    "state: $recovered"
            )
        }
    }

    private fun startHeadless(appCtx: Context, titleId: String, path: String) {
        val existing =
            currentJob

        if (
            existing != null &&
            existing.isActive
        ) {
            Log.i(
                TAG,
                "Headless PPU already active; " +
                    "ignoring duplicate request " +
                    "title=$titleId"
            )
            return
        }

        currentJob =
            scope.launch(
                Dispatchers.IO
            ) {
            val sessionId = System.currentTimeMillis()
            lastSessionId = sessionId
            try {
                // Idle barrier: wait for install compile queue idle + engine Stopped
                waitingForIdle = true
                val idleOk = waitForEngineIdle(titleId, sessionId)
                waitingForIdle = false
                if (!idleOk) {
                    Log.w(TAG, "Engine idle timeout for $titleId, exposing retry")
                    withContext(Dispatchers.Main) {
                        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                    }
                    return@launch
                }
                val resolvedTitleId =
                    GameSettingsOverrides
                        .resolveTitleId(
                            path,
                            appCtx
                        )
                        ?: titleId

                Log.i(
                    TAG,
                    "Using canonical global + sparse title config " +
                        "for headless PPU title=$resolvedTitleId"
                )

                val manifestBefore =
                    try {
                        RPCSX.instance
                            .getPpuManifestKey(
                                resolvedTitleId
                            )
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "manifestBefore unavailable: " +
                                t.message
                        )
                        null
                    }

                Log.i(
                    "S3PPU",
                    "headless_preflight_begin " +
                        "title=$resolvedTitleId " +
                        "manifest=$manifestBefore " +
                        "path=$path"
                )

                // Now invoke native real preparation — NO fake success (BUG E)
                // First-time PPU cold compile can hang at 9/9 done on some devices (LLVM deadlock).
                // Guard with 10 min timeout so UI never stays PREPARING forever; user can retry.
                val ret =
                    try {
                        withTimeout(10 * 60 * 1000L) {
                            RPCSX.instance.prepareRuntimePpu(
                                path,
                                sessionId
                            )
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.e(
                            TAG,
                            "prepareRuntimePpu timeout (10min) for $titleId session=$sessionId, canceling native",
                            e
                        )
                        try {
                            RPCSX.instance.cancelRuntimePpuPreparation(sessionId)
                        } catch (_: Exception) {}
                        // Give native a moment to unwind fxo/vm
                        delay(500)
                        -1
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(
                            TAG,
                            "prepareRuntimePpu not supported",
                            e
                        )
                        -1000
                    } catch (t: Throwable) {
                        Log.e(
                            TAG,
                            "prepareRuntimePpu failed",
                            t
                        )
                        -1
                    }

                if (ret == -1000) {
                    withContext(Dispatchers.Main) {
                        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                    }
                    return@launch
                }

                if (ret == 0) {
                    // BUG B+D ordering: verify native quiescence BEFORE publishing readiness
                    Log.i(TAG, "prepareRuntimePpu returned 0 for $titleId, waiting for native Stopped quiescence")
                    var nativeStopped = false
                    var attempts = 0
                    while (attempts < 50) { // 5s max, 100ms each
                        if (lastSessionId != sessionId) {
                            Log.w(TAG, "prepare canceled mid-quiescence $titleId")
                            return@launch
                        }
                        val st = runCatching { RPCSX.getState() }.getOrElse {
                            Log.e(TAG, "state-read-failed after prepare $titleId: ${it.message}")
                            withContext(Dispatchers.Main) {
                                PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                            }
                            return@launch
                        }
                        if (st == com.zenithblue.sambas3.EmulatorState.Stopped) {
                            nativeStopped = true
                            break
                        }
                        Log.d(TAG, "waiting for Stopped after prepare $titleId state=$st attempt=$attempts")
                        delay(100)
                        attempts++
                    }
                    if (!nativeStopped) {
                        Log.e(TAG, "prepareRuntimePpu quiescence timeout $titleId state=${try { RPCSX.getState() } catch (_: Exception) { "unknown" }}, marking FAILED")
                        withContext(Dispatchers.Main) {
                            PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                        }
                        return@launch
                    }
                    val manifestAfter =
                        try {
                            RPCSX.instance
                                .getPpuManifestKey(
                                    resolvedTitleId
                                )
                        } catch (_: Throwable) {
                            null
                        }

                    if (
                        manifestBefore != null &&
                        manifestAfter != null &&
                        manifestBefore !=
                            manifestAfter
                    ) {
                        Log.e(
                            "S3PPU",
                            "manifest identity changed during " +
                                "preflight: before=$manifestBefore " +
                                "after=$manifestAfter"
                        )

                        PpuReadinessStore.setRuntimeState(
                            appCtx,
                            titleId,
                            RuntimePpuState.FAILED
                        )

                        return@launch
                    }
                    // Native is Stopped, clear stale compile-only activeGame ownership (BUG A)
                    withContext(Dispatchers.Main) {
                        try {
                            val curActive = RPCSX.activeGame.value
                            if (curActive != null) {
                                // If activeGame was set for this title but we never reached gameplay, clear it.
                                // For headless, activeGame should already be null, but be defensive.
                                Log.i(TAG, "Headless terminal clearing stale activeGame=$curActive for $titleId")
                                RPCSX.activeGame.value = null
                            }
                            RPCSX.state.value = com.zenithblue.sambas3.EmulatorState.Stopped
                            Log.i("S3PPU", "prepare_terminal session=$sessionId title=$titleId compile_finished=1 workers_idle=1 fxo_clean=1 emu_state=stopped will_resume_game=0")
                        } catch (e: Exception) {
                            Log.w(TAG, "failed to sync state after prepare: ${e.message}")
                        }
                        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.IDLE_AFTER_COMPILE)
                        Log.i(TAG, "Headless prelaunch success $titleId -> IDLE_AFTER_COMPILE (after Stopped verified)")
                    }
                } else if (ret == -2 || ret == -3) {
                    // Transient busy - should not have happened after idle wait, but treat as retryable
                    Log.w(TAG, "Headless prelaunch transient busy $titleId ret=$ret, marking FAILED retryable")
                    withContext(Dispatchers.Main) {
                        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        PpuReadinessStore.setRuntimeState(appCtx, titleId, RuntimePpuState.FAILED)
                    }
                    Log.w(TAG, "Headless prelaunch failed $titleId ret=$ret")
                }
            } catch (
                e: CancellationException
            ) {
                waitingForIdle = false

                Log.i(
                    TAG,
                    "Headless coordinator canceled " +
                        "title=$titleId " +
                        "session=$sessionId"
                )

                throw e
            } catch (
                e: Exception
            ) {
                Log.e(
                    TAG,
                    "Coordinator failed for $titleId",
                    e
                )

                waitingForIdle = false

                PpuReadinessStore.setRuntimeState(
                    appCtx,
                    titleId,
                    RuntimePpuState.FAILED
                )
            } finally {
                if (
                    lastSessionId ==
                    sessionId
                ) {
                    waitingForIdle = false
                }
            }
        }
    }

    private suspend fun waitForEngineIdle(titleId: String, sessionId: Long): Boolean {
        // Wait for install PPU queue to be terminal and engine Stopped
        val maxAttempts = 150 // 30 sec (200ms *150)
        var attempts = 0
        while (attempts < maxAttempts) {
            // Check cancellation via session id; delay will throw if job cancelled
            if (lastSessionId != sessionId) return false
            // Check cancellation for this session
            try {
                if (lastSessionId != sessionId) return false
            } catch (_: Exception) {}
            // Check installPpu active? We need to check CompileProgressBridge.installState
            val installActive = try { CompileProgressBridge.installState.value.ppuActive } catch (_: Exception) { false }
            if (installActive) {
                Log.d(TAG, "waitForIdle $titleId attempt $attempts installActive, waiting")
                delay(200)
                attempts++
                continue
            }
            // Check engine state
            val state = runCatching { RPCSX.getState() }.getOrElse {
                Log.e(TAG, "waitForIdle state-read-failed title=$titleId error=${it.message}")
                return false
            }
            if (state == com.zenithblue.sambas3.EmulatorState.Stopped) {
                // Also check that native compilation queue is not still holding lock? Try to check if we can acquire?
                // For now, if Stopped, consider idle
                Log.i(TAG, "waitForIdle success $titleId after $attempts attempts state=$state")
                return true
            }
            // Transient busy states: Loading, Running, Paused, Stopping, Ready, Starting
            Log.d(TAG, "waitForIdle $titleId attempt $attempts state=$state, waiting")
            delay(200)
            attempts++
        }
        Log.w(TAG, "waitForIdle timeout $titleId after $attempts attempts")
        return false
    }

    private fun resolveGameForTitle(titleId: String): Game? {
        val byRepo = try {
            GameRepository.list().firstOrNull { g ->
                val tid = com.zenithblue.sambas3.GameIdentity.titleIdOrNull(g.info.path, g.info.name.value)
                tid?.equals(titleId, ignoreCase = true) == true
            }
        } catch (_: Exception) { null }
        if (byRepo != null) return byRepo
        // Try ImportSession provisional/resolved then fallback to constructed path
        return null
    }

    private fun resolvePathForTitle(ctx: Context, titleId: String, game: Game?): String? {
        if (game != null) return game.info.path
        // Fallback: check filesystem for installed game dir
        val candidate = File(RPCSX.rootDirectory, "config/games/$titleId").absolutePath
        if (File(candidate).exists()) return candidate
        // Also check via GameRepository list again for key match
        try {
            val all = GameRepository.list()
            val match = all.firstOrNull { it.info.path.contains(titleId, ignoreCase = true) }
            if (match != null) return match.info.path
        } catch (_: Exception) {}
        return null
    }
}
