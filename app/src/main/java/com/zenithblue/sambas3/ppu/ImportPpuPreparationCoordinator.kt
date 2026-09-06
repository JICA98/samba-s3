package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.EmulatorState
import com.zenithblue.sambas3.Game
import com.zenithblue.sambas3.GameIdentity
import com.zenithblue.sambas3.GameRepository
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Coordinates both Kotlin-owned PPU phases before RPCSXActivity is opened. */
object ImportPpuPreparationCoordinator {
    private const val TAG = "PpuCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    @Volatile
    var lastSessionId: Long = -1L
        private set

    @Volatile
    var waitingForIdle: Boolean = false
        private set

    @Volatile
    var activeTitleId: String? = null
        private set

    @Volatile
    var stopping: Boolean = false
        private set

    // Retained as a launch-presentation input; isolated workers do not require
    // the old long-running headless FGS deferral path.
    @Volatile
    var deferredForFgs: Boolean = false
        private set

    private val _coordinatorRevision = MutableStateFlow(0L)
    val coordinatorRevision: StateFlow<Long> = _coordinatorRevision.asStateFlow()

    private fun publishState(waiting: Boolean = waitingForIdle) {
        waitingForIdle = waiting
        _coordinatorRevision.value = _coordinatorRevision.value + 1L
    }

    /** Import terminal: persist INSTALL success and immediately queue PRELAUNCH batches. */
    fun onInstallPpuSuccess(context: Context, titleId: String?, gamePath: String? = null) {
        if (titleId.isNullOrBlank()) return
        val appContext = context.applicationContext
        PpuReadinessStore.setPreRuntimeState(appContext, titleId, PreRuntimePpuState.READY)
        if (PpuReadinessStore.getRuntimeState(appContext, titleId) != RuntimePpuState.COMPILING) {
            PpuReadinessStore.setRuntimeState(appContext, titleId, RuntimePpuState.NOT_STARTED)
        }
        val path = gamePath?.takeIf { it.isNotBlank() }
            ?: resolvePathForTitle(titleId, resolveGameForTitle(titleId))
        if (path == null) {
            Log.w(TAG, "Install PPU ready but game path unavailable for Runtime PPU title=$titleId")
            publishState()
            return
        }
        startRuntimeBatches(appContext, titleId, PpuCompilePathResolver.resolveForWorker(path, titleId))
    }

    /** Home/Launch Center action. It starts the required batch phase; it never boots gameplay. */
    fun requestPreparation(context: Context, game: Game): PpuUserAction {
        val appContext = context.applicationContext
        val titleId = runCatching {
            GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
                ?: GameIdentity.key(game.info.path, game.info.name.value)
        }.getOrDefault(game.info.path)
        val action = PpuUserActionDecision.decide(
            PpuActionInputs(
                preRuntime = runCatching { PpuReadinessStore.getPreRuntimeState(appContext, titleId) }
                    .getOrDefault(PreRuntimePpuState.NOT_DONE),
                runtime = runCatching { PpuReadinessStore.getRuntimeState(appContext, titleId) }
                    .getOrDefault(RuntimePpuState.NOT_STARTED),
                validatedByRealBootFrame = runCatching {
                    PpuReadinessStore.isRuntimeValidated(appContext, titleId)
                }.getOrDefault(false),
                installPpuActive = CompileProgressBridge.installState.value.ppuActive,
                prelaunchPpuActive = CompileProgressBridge.prelaunchState.value.ppuActive,
                runtimePpuActive = CompileProgressBridge.state.value.ppuActive,
                waitingForIdle = waitingForIdle,
            )
        )

        val compilePath = PpuCompilePathResolver.resolveForWorker(game.info.path, titleId)
        when (action) {
            PpuUserAction.REBUILD_INSTALL_PPU -> startInstallBatches(appContext, titleId, compilePath)
            PpuUserAction.PREPARE_RUNTIME,
            PpuUserAction.RETRY_RUNTIME_PREPARATION -> startRuntimeBatches(appContext, titleId, compilePath)
            else -> Unit
        }
        Log.i(TAG, "requestPreparation title=$titleId action=$action path=${game.info.path}")
        return action
    }

    /** User STOP: kill `:ppu_compile`, keep cache, surface retry on the stopped title. */
    fun requestStop(context: Context) {
        val appContext = context.applicationContext
        val hasJob = currentJob?.isActive == true ||
            CompileProgressBridge.installState.value.ppuActive ||
            CompileProgressBridge.prelaunchState.value.ppuActive ||
            CompileProgressBridge.state.value.ppuActive ||
            waitingForIdle ||
            stopping
        stopping = true
        publishState()
        Log.i(TAG, "requestStop title=$activeTitleId session=$lastSessionId hasJob=$hasJob")
        PpuInstallOrchestrator.requestCancel(appContext)
        PpuRuntimeOrchestrator.requestCancel(appContext)
        PpuWorkerProcessKiller.kill(appContext, null)
        val job = currentJob
        if (job == null || job.isCompleted) {
            markStoppedTitle(appContext, activeTitleId)
            stopping = false
            activeTitleId = null
            publishState(false)
        } else {
            job.cancel()
        }
    }

    private fun startInstallBatches(context: Context, titleId: String, path: String) {
        if (currentJob?.isActive == true) return
        val sessionId = System.currentTimeMillis()
        lastSessionId = sessionId
        activeTitleId = titleId
        stopping = false
        PpuInstallOrchestrator.prepareForNewJob()
        PpuRuntimeOrchestrator.prepareForNewJob()
        PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.IN_PROGRESS)
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.NOT_STARTED)
        publishState()
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                val installOk = PpuInstallOrchestrator.execute(context, titleId, path, sessionId)
                if (installOk && !stopping) {
                    PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.READY)
                    PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.NOT_STARTED)
                    lastSessionId = sessionId + 1
                    PpuRuntimeOrchestrator.execute(context, titleId, path, lastSessionId)
                }
            } finally {
                markStoppedTitle(context, titleId)
                stopping = false
                activeTitleId = null
                publishState(false)
            }
        }
    }

    private fun startRuntimeBatches(context: Context, titleId: String, path: String) {
        if (currentJob?.isActive == true) return
        if (CompileProgressBridge.prelaunchState.value.ppuActive) return
        val sessionId = System.currentTimeMillis()
        lastSessionId = sessionId
        activeTitleId = titleId
        stopping = false
        PpuRuntimeOrchestrator.prepareForNewJob()
        publishState()
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                var attempts = 0
                while (RPCSX.state.value != EmulatorState.Stopped && attempts < 150 && !stopping) {
                    publishState(true)
                    delay(200)
                    attempts++
                }
                if (stopping) return@launch
                if (RPCSX.state.value != EmulatorState.Stopped) {
                    PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
                    Log.w(TAG, "Runtime PPU idle timeout title=$titleId state=${RPCSX.state.value}")
                    return@launch
                }
                publishState(false)
                PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.COMPILING)
                PpuRuntimeOrchestrator.execute(context, titleId, path, sessionId)
            } finally {
                markStoppedTitle(context, titleId)
                stopping = false
                activeTitleId = null
                publishState(false)
            }
        }
    }

    fun cancel(sessionId: Long, context: Context? = null) {
        if (lastSessionId != sessionId && currentJob?.isActive != true) return
        stopping = true
        publishState()
        PpuInstallOrchestrator.requestCancel(context?.applicationContext)
        PpuRuntimeOrchestrator.requestCancel(context?.applicationContext)
        if (context != null) {
            PpuWorkerProcessKiller.kill(context.applicationContext, null)
        }
        currentJob?.cancel()
    }

    private fun markStoppedTitle(context: Context, titleId: String?) {
        if (titleId.isNullOrBlank()) return
        if (!stopping) {
            // Job finished normally; just make sure live flags are not stuck.
            return
        }
        val pre = runCatching { PpuReadinessStore.getPreRuntimeState(context, titleId) }
            .getOrDefault(PreRuntimePpuState.NOT_DONE)
        if (pre == PreRuntimePpuState.IN_PROGRESS) {
            PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.FAILED)
            CompileProgressBridge.updateInstallStateForExternalWorker(
                titleId = titleId,
                jobId = lastSessionId,
                moduleDone = 0,
                moduleTotal = 0,
                percent = 0,
                message = "Install PPU stopped — retry to resume",
                active = false,
                outcome = com.zenithblue.sambas3.CompileOutcome.CANCELED,
            )
        } else if (CompileProgressBridge.installState.value.ppuActive &&
            CompileProgressBridge.installState.value.titleId?.equals(titleId, ignoreCase = true) == true
        ) {
            CompileProgressBridge.updateInstallStateForExternalWorker(
                titleId = titleId,
                jobId = lastSessionId,
                moduleDone = CompileProgressBridge.installState.value.moduleDone,
                moduleTotal = CompileProgressBridge.installState.value.moduleTotal,
                percent = CompileProgressBridge.installState.value.ppuPercent,
                message = "Install PPU stopped — retry to resume",
                active = false,
                outcome = com.zenithblue.sambas3.CompileOutcome.CANCELED,
            )
        }
        val runtime = runCatching { PpuReadinessStore.getRuntimeState(context, titleId) }
            .getOrDefault(RuntimePpuState.NOT_STARTED)
        if (runtime == RuntimePpuState.COMPILING) {
            PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
            CompileProgressBridge.updatePrelaunchStateForExternalWorker(
                context = null,
                titleId = titleId,
                jobId = lastSessionId,
                moduleDone = 0,
                moduleTotal = 0,
                percent = 0,
                message = "Runtime PPU stopped — retry to resume",
                active = false,
                outcome = com.zenithblue.sambas3.CompileOutcome.CANCELED,
            )
        } else if (
            (CompileProgressBridge.prelaunchState.value.ppuActive &&
                CompileProgressBridge.prelaunchState.value.titleId?.equals(titleId, ignoreCase = true) == true) ||
            (CompileProgressBridge.state.value.ppuActive &&
                CompileProgressBridge.state.value.titleId?.equals(titleId, ignoreCase = true) == true)
        ) {
            CompileProgressBridge.updatePrelaunchStateForExternalWorker(
                context = null,
                titleId = titleId,
                jobId = lastSessionId,
                moduleDone = CompileProgressBridge.prelaunchState.value.moduleDone,
                moduleTotal = CompileProgressBridge.prelaunchState.value.moduleTotal,
                percent = CompileProgressBridge.prelaunchState.value.ppuPercent,
                message = "Runtime PPU stopped — retry to resume",
                active = false,
                outcome = com.zenithblue.sambas3.CompileOutcome.CANCELED,
            )
        }
        Log.i(TAG, "markStoppedTitle title=$titleId pre=$pre runtime=$runtime")
    }

    fun reconcileInterruptedState(context: Context) {
        if (currentJob?.isActive == true) return
        if (CompileProgressBridge.installState.value.ppuActive) return
        if (CompileProgressBridge.prelaunchState.value.ppuActive) return
        if (CompileProgressBridge.state.value.ppuActive) return
        if (RPCSX.state.value != EmulatorState.Stopped) return
        val app = context.applicationContext
        val recovered = PpuReadinessStore.recoverInterruptedRuntimePreparations(app)
        if (recovered.isEmpty()) return
        // Keep install/runtime session files so PREPARE/RETRY resumes cached modules.
        recovered.forEach { key ->
            val pre = PpuReadinessStore.getPreRuntimeState(app, key)
            val runtime = PpuReadinessStore.getRuntimeState(app, key)
            if (pre == PreRuntimePpuState.FAILED) {
                CompileProgressBridge.updateInstallStateForExternalWorker(
                    titleId = key,
                    jobId = lastSessionId,
                    moduleDone = 0,
                    moduleTotal = 0,
                    percent = 0,
                    message = "Install PPU interrupted — retry to resume",
                    active = false,
                    outcome = com.zenithblue.sambas3.CompileOutcome.FAILED,
                )
            }
            if (runtime == RuntimePpuState.FAILED) {
                CompileProgressBridge.updatePrelaunchStateForExternalWorker(
                    context = null,
                    titleId = key,
                    jobId = lastSessionId,
                    moduleDone = 0,
                    moduleTotal = 0,
                    percent = 0,
                    message = "Runtime PPU interrupted — retry to resume",
                    active = false,
                    outcome = com.zenithblue.sambas3.CompileOutcome.FAILED,
                )
            }
        }
        publishState(false)
    }

    private fun resolveGameForTitle(titleId: String): Game? = runCatching {
        GameRepository.list().firstOrNull { game ->
            GameIdentity.titleIdOrNull(game.info.path, game.info.name.value)
                ?.equals(titleId, ignoreCase = true) == true
        }
    }.getOrNull()

    private fun resolvePathForTitle(titleId: String, game: Game?): String? {
        game?.info?.path?.takeIf { it.isNotBlank() }?.let {
            return PpuCompilePathResolver.resolveForWorker(it, titleId)
        }
        val candidate = File(RPCSX.rootDirectory, "config/games/$titleId")
        return candidate.absolutePath.takeIf { candidate.isDirectory }
    }
}
