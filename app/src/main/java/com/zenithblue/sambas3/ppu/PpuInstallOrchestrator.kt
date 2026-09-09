package com.zenithblue.sambas3.ppu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zenithblue.sambas3.CompileOutcome
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.ImportPhase
import com.zenithblue.sambas3.ImportSessionStore
import com.zenithblue.sambas3.InstallPpuTerminalLogic
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.ProgressRepository
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object PpuInstallOrchestrator {
    private const val TAG = "PpuOrchestrator"
    private const val NOTIF_INSTALL = 3000L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSessionId: Long? = null
    @Volatile
    private var activeLogicalJobId: Long? = null
    @Volatile
    @android.annotation.SuppressLint("StaticFieldLeak") // Connection owns applicationContext and is cleared on terminal paths.
    private var activeConnection: PpuBatchWorkerConnection? = null
    @Volatile
    private var isCanceled = false

    fun cancel(sessionId: Long) {
        val matches = sessionId == activeSessionId || sessionId == activeLogicalJobId
        if ((activeSessionId != null || activeLogicalJobId != null) && !matches) {
            Log.i(TAG, "Ignoring stale cancel session=$sessionId active=$activeSessionId job=$activeLogicalJobId")
            PpuDiagnosticLog.emit("stale_stop_ignored", extras = mapOf("sessionId" to sessionId, "active" to activeSessionId))
            return
        }
        requestCancel()
        Log.i(TAG, "Cancellation requested for session=$sessionId active=$activeSessionId")
    }

    fun prepareForNewJob() {
        isCanceled = false
    }

    fun requestCancel(context: Context? = null) {
        isCanceled = true
        val sid = activeSessionId ?: activeLogicalJobId ?: 0L
        val conn = activeConnection
        Log.i(TAG, "requestCancel session=$sid pid=${conn?.workerPid}")
        PpuDiagnosticLog.emit("cancel_request", extras = mapOf("sessionId" to sid, "pid" to conn?.workerPid))
        conn?.requestCancel(sid)
    }

    suspend fun execute(
        context: Context,
        titleId: String,
        gamePath: String,
        logicalJobId: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val safeTitle = titleId.trim()
        if (safeTitle.isEmpty() || gamePath.isBlank()) {
            Log.e(TAG, "Invalid titleId ($titleId) or gamePath ($gamePath)")
            return@withContext false
        }

        val fileLock = PpuProcessFileLock.tryAcquire(appContext, safeTitle)
        if (fileLock == null) {
            Log.e(TAG, "Could not acquire compile file lock for $safeTitle — another worker or process is active")
            return@withContext false
        }

        try {
            if (isCanceled) {
                Log.i(TAG, "execute aborted — already canceled title=$safeTitle")
                markCanceled(appContext, safeTitle, logicalJobId)
                return@withContext false
            }
            activeLogicalJobId = logicalJobId
            val currentManifestKey = runCatching { RPCSX.instance.getPpuManifestKey(safeTitle) }.getOrNull() ?: ""
            var sessionLoaded = PpuInstallSessionStore.load(appContext)

            if (sessionLoaded != null && (sessionLoaded.titleId != safeTitle || sessionLoaded.gamePath != gamePath || sessionLoaded.manifestKey != currentManifestKey)) {
                Log.w(TAG, "Manifest or title mismatch (old=${sessionLoaded.titleId} new=$safeTitle) — resetting session")
                PpuInstallSessionStore.clear(appContext)
                sessionLoaded = null
            }

            // Every retry is a fresh attempt. Native revalidates the retained cache;
            // persisted UI counters must never be accepted as completion evidence.
            val resumedBatchSize = sessionLoaded?.batchSize ?: PpuBatchPolicy.DEFAULT_BATCH_SIZE
            val sessionId = logicalJobId
            activeSessionId = sessionId

            var session = PpuInstallSession(
                sessionId = sessionId,
                jobId = logicalJobId,
                titleId = safeTitle,
                gamePath = gamePath,
                manifestKey = currentManifestKey,
                batchSize = resumedBatchSize,
                phase = PpuSessionPhase.CREATED,
                attemptId = logicalJobId.toString(),
                logicalSessionId = logicalJobId.toString(),
            )
            PpuInstallSessionStore.save(appContext, session)

            var totalModules = session.totalModules
            var completedModules = session.completedModules
            var batchIndex = session.batchIndex
            var currentBatchSize = session.batchSize
            var consecutiveMinFailures = 0
            var bindFailures = 0
            val noProgress = NoProgressGuard(PpuWorkerControlPolicy.ZERO_PROGRESS_BATCH_LIMIT)

            Log.i(
                "S3PPUSESSION",
                "session=$sessionId title=$safeTitle state=START total=$totalModules cached=$completedModules batch=$batchIndex"
            )

            val user = try { UserRepository.getUserFromSettings() } catch (_: Exception) { "00000001" }

            while (!isCanceled) {
                val batchFinishedDeferred = CompletableDeferred<JSONObject>()
                val processExitDeferred = CompletableDeferred<WorkerDeathReason>()

                var conn: PpuBatchWorkerConnection? = null
                conn = PpuBatchWorkerConnection(appContext) { deathReason ->
                    if (deathReason !is WorkerDeathReason.ExpectedAfterResult) {
                        batchFinishedDeferred.complete(
                            JSONObject().put("status", "unexpected_death").put("message", "worker_died")
                        )
                    }
                    processExitDeferred.complete(deathReason)
                }

                val bound = conn.bind()
                if (!bound) {
                    Log.e(TAG, "Failed to bind to PpuBatchWorkerService for batch=$batchIndex")
                    conn.releaseBindingAfterExit()
                    bindFailures++
                    if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                        markFailed(appContext, safeTitle, logicalJobId, "Could not start PPU worker")
                        return@withContext false
                    }
                    delay(1000)
                    continue
                }
                activeConnection = conn

                val worker = try {
                    val ready = withTimeoutOrNull(PpuWorkerControlPolicy.STARTUP_TIMEOUT_MS) {
                        conn.connectionReady.await()
                    }
                    if (ready == null) {
                        Log.e(TAG, "Worker startup timeout batch=$batchIndex")
                        awaitVerifiedExitOrQuarantine(
                            conn, appContext, safeTitle, logicalJobId,
                            signalImmediately = true,
                            reason = "Worker startup timeout",
                        )
                        conn.releaseBindingAfterExit()
                        if (activeConnection === conn) activeConnection = null
                        bindFailures++
                        if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                            markFailed(appContext, safeTitle, logicalJobId, "Worker startup timeout")
                            return@withContext false
                        }
                        delay(1000)
                        continue
                    }
                    ready
                } catch (e: CancellationException) {
                    isCanceled = true
                    withContext(NonCancellable) {
                        awaitVerifiedExitOrQuarantine(
                            conn, appContext, safeTitle, logicalJobId,
                            signalImmediately = false,
                            reason = "Worker exit was not observed after cancellation",
                        )
                        conn.releaseBindingAfterExit()
                        markCanceled(appContext, safeTitle, logicalJobId)
                    }
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Worker connection await failed: ${e.message}")
                    conn.releaseBindingAfterExit()
                    bindFailures++
                    if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                        markFailed(appContext, safeTitle, logicalJobId, e.message ?: "Worker connection failed")
                        return@withContext false
                    }
                    delay(1000)
                    continue
                }
                bindFailures = 0
                runCatching {
                    val pid = worker.workerPid
                    if (pid > 0) conn.workerPid = pid
                }
                if (isCanceled) {
                    conn.requestCancel(sessionId)
                    awaitVerifiedExitOrQuarantine(
                        conn, appContext, safeTitle, logicalJobId,
                        signalImmediately = false,
                        reason = "Worker exit was not observed after cancellation",
                    )
                    conn.releaseBindingAfterExit()
                    markCanceled(appContext, safeTitle, logicalJobId)
                    return@withContext false
                }

                val callback = object : IPpuBatchCallback.Stub() {
                    override fun onBatchStarted(
                        cbSessionId: Long,
                        workerPid: Int,
                        workerInstanceId: String?,
                        cbBatchIndex: Int
                    ) {
                        if (cbSessionId != sessionId || cbBatchIndex != batchIndex) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "started"))
                            return
                        }
                        if (workerPid > 0) conn.workerPid = workerPid
                        conn.workerInstanceId = workerInstanceId
                        Log.i(
                            "S3PPUBATCH",
                            "batch=$cbBatchIndex pid=$workerPid worker=$workerInstanceId state=STARTED"
                        )
                    }

                    override fun onProgress(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbTotal: Int,
                        cbCompleted: Int,
                        message: String?
                    ) {
                        if (isCanceled || cbSessionId != sessionId || cbJobId != logicalJobId) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "progress"))
                            return
                        }
                        // Native callback counters can reset between ELF/PRX scopes.
                        // Keep the last audited batch receipt as numeric authority.
                        updateProgressUi(
                            appContext,
                            safeTitle,
                            logicalJobId,
                            OverallProgress(totalModules, completedModules, if (totalModules > 0) completedModules * 100 / totalModules else 0),
                            message ?: "Compiling PPU objects… $completedModules validated",
                        )
                    }

                    override fun onBatchFinished(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbBatchIndex: Int,
                        resultJson: String?
                    ) {
                        if (cbSessionId != sessionId || cbJobId != logicalJobId || cbBatchIndex != batchIndex) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "finished"))
                            return
                        }
                        conn.isResultReceived = true
                        val json = try {
                            JSONObject(resultJson ?: "{}")
                        } catch (e: Exception) {
                            JSONObject().put("status", "failed").put("message", e.message)
                        }
                        batchFinishedDeferred.complete(json)
                    }
                }

                session = session.copy(
                    batchIndex = batchIndex,
                    batchSize = currentBatchSize,
                    phase = PpuSessionPhase.BATCH_RUNNING,
                    updatedMs = System.currentTimeMillis()
                )
                PpuInstallSessionStore.save(appContext, session)

                try {
                    worker.startBatch(
                        sessionId,
                        logicalJobId,
                        safeTitle,
                        gamePath,
                        user,
                        batchIndex,
                        currentBatchSize,
                        RPCSX.COMPILE_ORIGIN_INSTALL,
                        currentManifestKey,
                        callback
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "worker.startBatch threw: ${e.message}")
                    batchFinishedDeferred.complete(
                        JSONObject().put("status", "failed").put("message", e.message)
                    )
                }

                val liveTicker = launch {
                    while (isActive && !batchFinishedDeferred.isCompleted && !isCanceled) {
                        delay(1000)
                        val onDisk = countCacheObjects(appContext, safeTitle)
                        PpuDiagnosticLog.emit(
                            "cache_scan",
                            titleId = safeTitle,
                            extras = mapOf("onDisk" to onDisk, "completed" to completedModules),
                        )
                    }
                }

                val resultObj = try {
                    batchFinishedDeferred.await()
                } catch (e: CancellationException) {
                    isCanceled = true
                    liveTicker.cancel()
                    withContext(NonCancellable) {
                        conn.requestCancel(sessionId)
                        awaitVerifiedExitOrQuarantine(
                            conn, appContext, safeTitle, logicalJobId,
                            signalImmediately = false,
                            reason = "Worker exit was not observed after cancellation",
                        )
                        conn.releaseBindingAfterExit()
                        markCanceled(appContext, safeTitle, logicalJobId)
                    }
                    throw e
                } catch (e: Exception) {
                    JSONObject().put("status", "failed").put("message", e.message)
                } finally {
                    liveTicker.cancel()
                }

                val deathReason = withTimeoutOrNull(PpuWorkerControlPolicy.EXIT_GRACE_MS) {
                    processExitDeferred.await()
                } ?: awaitVerifiedExitOrQuarantine(
                    conn, appContext, safeTitle, logicalJobId,
                    signalImmediately = true,
                    reason = "Worker exit was not observed after result",
                )
                conn.releaseBindingAfterExit()
                if (activeConnection === conn) activeConnection = null

                val oldWorkerDead = when (deathReason) {
                    is WorkerDeathReason.ExpectedAfterResult -> 1
                    is WorkerDeathReason.Unexpected,
                    is WorkerDeathReason.NullBinding,
                    is WorkerDeathReason.BindingDied -> 1
                }
                Log.i("S3PPUIPC", "old_worker_dead=$oldWorkerDead")

                val report = PpuNativeBatchReport.parse(resultObj.toString())
                val status = report.status
                if (status == "all_complete" && !report.provesCompletion) {
                    markFailed(appContext, safeTitle, logicalJobId, "Native completion receipt was not auditable")
                    return@withContext false
                }
                val workerTotal = report.totalModules
                val cachedAfter = report.cachedAfter
                val completedBefore = completedModules
                val totalBefore = totalModules

                if (workerTotal > totalModules) totalModules = workerTotal
                if (cachedAfter > completedModules) completedModules = cachedAfter

                val reduced = PpuOverallProgressReducer.reduceBatchFinished(
                    titleTotal = totalModules,
                    lastKnownCompleted = completedModules,
                    workerTotal = workerTotal,
                    cachedAfter = cachedAfter
                )
                completedModules = maxOf(completedModules, reduced.completedModules)
                totalModules = maxOf(totalModules, reduced.totalModules)
                updateProgressUi(appContext, safeTitle, logicalJobId, reduced)

                session = session.copy(
                    totalModules = totalModules,
                    completedModules = completedModules,
                    phase = if (status == "all_complete") PpuSessionPhase.COMPLETED else PpuSessionPhase.MORE_WORK,
                    updatedMs = System.currentTimeMillis()
                )
                PpuInstallSessionStore.save(appContext, session)

                if (isCanceled || status == "canceled") {
                    Log.i("S3PPUSESSION", "state=CANCELED job=$logicalJobId")
                    markCanceled(appContext, safeTitle, logicalJobId)
                    return@withContext false
                }

                if (deathReason !is WorkerDeathReason.ExpectedAfterResult) {
                    val onDiskCount = countCacheObjects(appContext, safeTitle)
                    if (onDiskCount > completedModules) {
                        Log.i(TAG, "Cache advanced despite crash: $completedModules -> $onDiskCount")
                        completedModules = onDiskCount
                        val reduced = PpuOverallProgressReducer.reduceBatchFinished(
                            titleTotal = totalModules,
                            lastKnownCompleted = completedModules,
                            workerTotal = totalModules,
                            cachedAfter = completedModules
                        )
                        updateProgressUi(appContext, safeTitle, logicalJobId, reduced)
                    }
                    val unexpected = deathReason as? WorkerDeathReason.Unexpected
                    Log.w(TAG, "Worker died unexpectedly pid=${unexpected?.lastWorkerPid ?: conn.workerPid} inst=${unexpected?.lastInstanceId ?: conn.workerInstanceId}")
                    val oldSize = currentBatchSize
                    currentBatchSize = PpuBatchPolicy.nextBatchSizeOnFailure(currentBatchSize)
                    Log.i(TAG, "Batch fallback $oldSize -> $currentBatchSize")
                    if (oldSize == PpuBatchPolicy.MIN_BATCH_SIZE) {
                        consecutiveMinFailures++
                        if (PpuBatchPolicy.shouldFailPermanently(consecutiveMinFailures)) {
                            Log.e(TAG, "Repeated failures at min batch size — failing logical job")
                            markFailed(appContext, safeTitle, logicalJobId, "Repeated worker crash")
                            return@withContext false
                        }
                    } else {
                        consecutiveMinFailures = 0
                    }
                    delay(500)
                    continue
                }

                consecutiveMinFailures = 0

                when (status) {
                    "all_complete" -> {
                        Log.i("S3PPUSESSION", "state=FINAL_COMPLETED job=$logicalJobId total=$totalModules")
                        markCompleted(appContext, safeTitle, logicalJobId, totalModules)
                        return@withContext true
                    }
                    "more_work" -> {
                        val newly = (completedModules - completedBefore).coerceAtLeast(0)
                        val discoveryAdvanced = totalModules > totalBefore || report.remainingUncached > 0 && newly > 0
                        if (noProgress.completedBatch(newly.toLong(), discoveryAdvanced || newly > 0)) {
                            markFailed(appContext, safeTitle, logicalJobId, "Repeated zero-progress batches")
                            return@withContext false
                        }
                        batchIndex++
                        delay(200)
                    }
                    "canceled" -> {
                        Log.i("S3PPUSESSION", "state=CANCELED job=$logicalJobId")
                        markCanceled(appContext, safeTitle, logicalJobId)
                        return@withContext false
                    }
                    else -> {
                        Log.e(TAG, "Batch reported error: ${resultObj.optString("message")}")
                        markFailed(appContext, safeTitle, logicalJobId, resultObj.optString("message"))
                        return@withContext false
                    }
                }
            }

            if (isCanceled) {
                markCanceled(appContext, safeTitle, logicalJobId)
                return@withContext false
            }

            true
        } catch (e: CancellationException) {
            isCanceled = true
            markCanceled(appContext, safeTitle, logicalJobId)
            throw e
        } finally {
            activeSessionId = null
            activeLogicalJobId = null
            activeConnection = null
            fileLock.close()
        }
    }

    private fun updateProgressUi(
        context: Context,
        titleId: String,
        jobId: Long,
        progress: OverallProgress,
        message: String? = null,
    ) {
        val cur = CompileProgressBridge.installState.value
        val previous = if (cur.ppuActive &&
            (cur.titleId.isNullOrBlank() || cur.titleId.equals(titleId, ignoreCase = true))
        ) {
            OverallProgress(cur.moduleTotal, cur.moduleDone, cur.ppuPercent)
        } else {
            null
        }
        val merged = PpuOverallProgressReducer.mergeMonotonic(previous, progress)
        val total = merged.totalModules
        val done = merged.completedModules
        val msg = message ?: if (total > 0) "module $done of $total" else "module $done"
        val remaining = PpuRemainingTimeTracker.observeInstall(titleId, done, total, active = true)
        val notifMsg = PpuRemainingTime.progressLine(msg, remaining)

        Log.i("S3PPUPROG", "done=$done total=$total remaining=${remaining ?: "-"}")

        // 1. Update notification 3000
        ProgressRepository.onProgressEvent(NOTIF_INSTALL, done.toLong(), total.toLong(), notifMsg)

        // 2. Update CompileProgressBridge.installState
        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = done,
            moduleTotal = total,
            percent = merged.percent,
            message = msg,
            active = true,
            remainingLabel = remaining,
        )

        // 3. Update ImportSessionStore
        ImportSessionStore.updatePhase(NOTIF_INSTALL, ImportPhase.COMPILING_PPU, resolvedTitleId = titleId)
    }

    private fun markCompleted(context: Context, titleId: String, jobId: Long, total: Int) {
        PpuRemainingTimeTracker.resetInstall()
        PpuInstallSessionStore.clear(context)

        // Terminal logic decision
        val decision = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.COMPLETED,
            terminalTitleId = titleId,
            terminalJobId = jobId,
            expectedTitleId = titleId,
            expectedJobId = jobId
        )

        Log.i(TAG, "markCompleted decision=${decision.markPreRuntimeReady} reason=${decision.reason}")

        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = total,
            moduleTotal = total,
            percent = 100,
            message = "PPU compilation complete",
            active = false,
            outcome = CompileOutcome.COMPLETED
        )

        if (decision.markPreRuntimeReady) {
            PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.READY)
            PpuReadinessStore.setRuntimeState(context, titleId, com.zenithblue.sambas3.RuntimePpuState.NOT_STARTED)
            ImportSessionStore.updatePhase(NOTIF_INSTALL, ImportPhase.READY, resolvedTitleId = titleId)
            mainHandler.postDelayed({ ImportSessionStore.remove(NOTIF_INSTALL) }, 1200)
        }
    }

    private fun markFailed(context: Context, titleId: String, jobId: Long, reason: String) {
        PpuRemainingTimeTracker.resetInstall()
        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = 0,
            moduleTotal = 0,
            percent = 0,
            message = "PPU compilation failed: $reason",
            active = false,
            outcome = CompileOutcome.FAILED
        )
        PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.FAILED)
        ImportSessionStore.remove(NOTIF_INSTALL)
    }

    private fun markCanceled(context: Context, titleId: String, jobId: Long) {
        PpuRemainingTimeTracker.resetInstall()
        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = 0,
            moduleTotal = 0,
            percent = 0,
            message = "Install PPU stopped — retry to resume",
            active = false,
            outcome = CompileOutcome.CANCELED
        )
        PpuReadinessStore.setPreRuntimeState(context, titleId, PreRuntimePpuState.FAILED)
        ImportSessionStore.remove(NOTIF_INSTALL)
    }

    private fun countCacheObjects(context: Context, titleId: String): Int {
        return try {
            var root = RPCSX.rootDirectory
            if (root.isEmpty()) {
                root = context.getExternalFilesDir(null)?.toString() ?: ""
            }
            if (root.isNotEmpty() && !root.endsWith("/")) root += "/"
            val dir = java.io.File(root, "cache/cache/$titleId")
            if (!dir.exists()) return 0
            var total = 0
            dir.listFiles()?.forEach { sub ->
                if (sub.isDirectory) {
                    total += sub.list()?.count { name ->
                        name.endsWith(".obj") || name.endsWith(".obj.gz")
                    } ?: 0
                } else if (sub.name.endsWith(".obj") || sub.name.endsWith(".obj.gz")) {
                    total++
                }
            }
            total
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun awaitVerifiedExitOrQuarantine(
        connection: PpuBatchWorkerConnection,
        context: Context,
        titleId: String,
        jobId: Long,
        signalImmediately: Boolean,
        reason: String,
    ): WorkerDeathReason {
        if (!signalImmediately) {
            withTimeoutOrNull(PpuWorkerControlPolicy.CANCEL_GRACE_MS) {
                connection.awaitVerifiedExit()
            }?.let { return it }
        }
        connection.signalVerifiedInstance()
        withTimeoutOrNull(PpuWorkerControlPolicy.EXIT_GRACE_MS) {
            connection.awaitVerifiedExit()
        }?.let { return it }

        PpuDiagnosticLog.emit("stop_failed_keep_lease", titleId = titleId, pid = connection.workerPid)
        markFailed(context, titleId, jobId, reason)
        ImportPpuPreparationCoordinator.onWorkerExitUnverified(titleId, jobId)
        // Deliberately unbounded: ownership and the process lock remain held until
        // Binder supplies actual death evidence. Retry must not overlap this worker.
        return connection.awaitVerifiedExit()
    }
}
