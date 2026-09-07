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
    private val owners = PpuPreparationOwners.registry

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
                waitingForIdle = waitingForIdle || stopping || hasActiveOwner(),
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

    /** User STOP: scoped cancel, keep cache, surface retry after verified worker exit. */
    fun requestStop(context: Context) {
        val appContext = context.applicationContext
        val owner = owners.peek()
        if (owner != null && !owner.contract.requestStop(owner.key)) {
            Log.i(TAG, "requestStop ignored — owner already ${owner.contract.stage}")
            return
        }
        stopping = true
        publishState()
        val session = owner?.sessionNumeric ?: lastSessionId
        Log.i(TAG, "requestStop title=${owner?.titleId ?: activeTitleId} session=$session")
        PpuDiagnosticLog.emit(
            "stop_requested",
            titleId = owner?.titleId ?: activeTitleId,
            sessionId = owner?.key?.sessionId,
            attemptId = owner?.key?.attemptId,
        )
        PpuInstallOrchestrator.cancel(session)
        PpuRuntimeOrchestrator.cancel(session)
        val job = currentJob
        if (job == null || job.isCompleted) {
            if (owner == null || owner.retired) {
                markStoppedTitle(appContext, activeTitleId)
                stopping = false
                activeTitleId = null
                publishState(false)
            }
        } else {
            job.cancel()
        }
    }

    fun requestInstallFromImport(context: Context, titleId: String, path: String) {
        startInstallBatches(context.applicationContext, titleId, path)
    }

    fun hasActiveOwner(): Boolean = owners.peek()?.let { !it.retired } == true

    /** Keeps Start/Retry blocked when a signaled worker has not produced real death evidence. */
    fun onWorkerExitUnverified(titleId: String, sessionNumeric: Long) {
        val owner = owners.peek() ?: return
        if (owner.sessionNumeric != sessionNumeric ||
            !owner.titleId.equals(titleId, ignoreCase = true) ||
            owner.retired
        ) return
        owner.stopFailed = true
        stopping = true
        activeTitleId = owner.titleId
        publishState()
        PpuDiagnosticLog.emit(
            "worker_exit_quarantined",
            titleId = owner.titleId,
            sessionId = owner.key.sessionId,
            attemptId = owner.key.attemptId,
        )
    }

    private fun startInstallBatches(context: Context, titleId: String, path: String) {
        val owner = admitOwner(titleId, path, PpuKind.INSTALL) ?: return
        lastSessionId = owner.sessionNumeric
        activeTitleId = titleId
        stopping = false
        PpuInstallOrchestrator.prepareForNewJob()
        PpuRuntimeOrchestrator.prepareForNewJob()
        PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.IN_PROGRESS)
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.NOT_STARTED)
        publishStarting(titleId, owner.sessionNumeric, install = true)
        publishState()
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                val installOk = PpuInstallOrchestrator.execute(context, titleId, path, owner.sessionNumeric)
                if (installOk && owners.stillOwner(owner) && !stopping) {
                    PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.READY)
                    PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.NOT_STARTED)
                    lastSessionId = owner.sessionNumeric
                    PpuRuntimeOrchestrator.execute(context, titleId, path, owner.sessionNumeric)
                }
            } finally {
                if (owners.stillOwner(owner)) {
                    markStoppedTitle(context, titleId)
                    stopping = false
                    activeTitleId = null
                    publishState(false)
                    owners.retire(owner)
                    PpuDiagnosticLog.emit("lock_released", titleId = titleId, attemptId = owner.key.attemptId)
                }
            }
        }
    }

    private fun startRuntimeBatches(context: Context, titleId: String, path: String) {
        val owner = admitOwner(titleId, path, PpuKind.PRELAUNCH) ?: return
        lastSessionId = owner.sessionNumeric
        activeTitleId = titleId
        stopping = false
        PpuRuntimeOrchestrator.prepareForNewJob()
        publishStarting(titleId, owner.sessionNumeric, install = false)
        publishState()
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                var attempts = 0
                while (RPCSX.state.value != EmulatorState.Stopped && attempts < 150 && !stopping) {
                    publishState(true)
                    delay(200)
                    attempts++
                }
                if (stopping || !owners.stillOwner(owner)) return@launch
                if (RPCSX.state.value != EmulatorState.Stopped) {
                    PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
                    Log.w(TAG, "Runtime PPU idle timeout title=$titleId state=${RPCSX.state.value}")
                    return@launch
                }
                publishState(false)
                PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.COMPILING)
                PpuRuntimeOrchestrator.execute(context, titleId, path, owner.sessionNumeric)
            } finally {
                if (owners.stillOwner(owner)) {
                    markStoppedTitle(context, titleId)
                    stopping = false
                    activeTitleId = null
                    publishState(false)
                    owners.retire(owner)
                    PpuDiagnosticLog.emit("lock_released", titleId = titleId, attemptId = owner.key.attemptId)
                }
            }
        }
    }

    fun cancel(sessionId: Long, context: Context? = null) {
        val owner = owners.peek()
        if (owner != null && owner.sessionNumeric != sessionId) {
            PpuDiagnosticLog.emit("stale_stop_ignored", extras = mapOf("sessionId" to sessionId))
            return
        }
        stopping = true
        publishState()
        PpuInstallOrchestrator.cancel(sessionId)
        PpuRuntimeOrchestrator.cancel(sessionId)
        currentJob?.cancel()
    }

    private fun admitOwner(titleId: String, path: String, kind: PpuKind): PpuOwnerRegistry.Owner? {
        val cur = owners.peek()
        if (cur != null && !cur.retired) {
            Log.i(TAG, "admit rejected — owner still held title=${cur.titleId} stage=${cur.contract.stage}")
            return null
        }
        val manifest = runCatching { RPCSX.instance.getPpuManifestKey(titleId) }.getOrNull().orEmpty()
        if (manifest.isBlank() || manifest == "unknown") {
            Log.e(TAG, "admit rejected — authoritative PPU manifest unavailable title=$titleId")
            PpuDiagnosticLog.emit("attempt_rejected", titleId = titleId, reason = "manifest_unavailable")
            return null
        }
        val key = AttemptKey.fresh(titleId, manifest, kind)
        val numeric = java.util.UUID.fromString(key.sessionId).mostSignificantBits and Long.MAX_VALUE
        val next = PpuOwnerRegistry.Owner(
            key = key,
            sessionNumeric = if (numeric == 0L) 1L else numeric,
            titleId = titleId,
            compilePath = path,
            contract = PreparationContract(key),
            createdAtMs = android.os.SystemClock.elapsedRealtime(),
        )
        val admitted = owners.admit(next)
        if (admitted == null) return null
        PpuDiagnosticLog.emit(
            "attempt_admitted",
            titleId = titleId,
            sessionId = key.sessionId,
            attemptId = key.attemptId,
            phase = kind.name,
            manifestId = manifest,
        )
        return admitted
    }

    private fun publishStarting(titleId: String, jobId: Long, install: Boolean) {
        val msg = "Starting PPU worker"
        if (install) {
            CompileProgressBridge.updateInstallStateForExternalWorker(
                titleId = titleId,
                jobId = jobId,
                moduleDone = 0,
                moduleTotal = 0,
                percent = 0,
                message = msg,
                active = true,
            )
        } else {
            CompileProgressBridge.updatePrelaunchStateForExternalWorker(
                context = null,
                titleId = titleId,
                jobId = jobId,
                moduleDone = 0,
                moduleTotal = 0,
                percent = 0,
                message = msg,
                active = true,
            )
        }
        PpuDiagnosticLog.emit("starting_published", titleId = titleId, extras = mapOf("install" to install))
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
        val owner = owners.peek()
        if (owner != null && !owner.retired) return
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
