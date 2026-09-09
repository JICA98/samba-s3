package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.CompileOutcome
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Kotlin-owned PRELAUNCH PPU pipeline. Every bounded batch runs in a fresh
 * :ppu_compile process so LLVM arenas are reclaimed before the next batch.
 */
object PpuRuntimeOrchestrator {
    private const val TAG = "PpuRuntimeBatch"

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var activeLogicalJobId: Long? = null

    @Volatile
    @android.annotation.SuppressLint("StaticFieldLeak") // Connection owns applicationContext and is cleared on terminal paths.
    private var activeConnection: PpuBatchWorkerConnection? = null

    @Volatile
    private var canceled = false

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
        canceled = false
    }

    fun requestCancel(context: Context? = null) {
        canceled = true
        val sid = activeSessionId ?: activeLogicalJobId ?: 0L
        val conn = activeConnection
        Log.i(TAG, "requestCancel session=$sid pid=${conn?.workerPid}")
        PpuDiagnosticLog.emit("cancel_request", extras = mapOf("sessionId" to sid, "pid" to conn?.workerPid, "phase" to "PRELAUNCH"))
        conn?.requestCancel(sid)
    }

    suspend fun execute(
        context: Context,
        titleId: String,
        gamePath: String,
        logicalJobId: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val safeTitle = titleId.trim()
        if (safeTitle.isEmpty() || gamePath.isBlank()) return@withContext false

        var fileLock = PpuProcessFileLock.tryAcquire(appContext, safeTitle)
        var lockAttempt = 0
        while (fileLock == null && lockAttempt < 50) {
            delay(100)
            lockAttempt++
            fileLock = PpuProcessFileLock.tryAcquire(appContext, safeTitle)
        }
        if (fileLock == null) {
            markFailed(appContext, safeTitle, logicalJobId, "Another PPU job is active")
            return@withContext false
        }
        val acquiredLock = fileLock

        try {
            if (canceled) {
                Log.i(TAG, "execute aborted — already canceled title=$safeTitle")
                markCanceled(appContext, safeTitle, logicalJobId)
                return@withContext false
            }
            activeLogicalJobId = logicalJobId
            val manifestKey = runCatching { RPCSX.instance.getPpuManifestKey(safeTitle) }
                .getOrNull().orEmpty()
            var loaded = PpuInstallSessionStore.load(appContext, PpuBatchKind.RUNTIME)
            if (loaded != null &&
                (loaded.titleId != safeTitle || loaded.gamePath != gamePath || loaded.manifestKey != manifestKey)
            ) {
                PpuInstallSessionStore.clear(appContext, PpuBatchKind.RUNTIME)
                loaded = null
            }

            val resumedBatchSize = loaded?.batchSize ?: PpuBatchPolicy.DEFAULT_BATCH_SIZE
            val sessionId = logicalJobId
            activeSessionId = sessionId
            var session = PpuInstallSession(
                kind = PpuBatchKind.RUNTIME,
                sessionId = sessionId,
                jobId = logicalJobId,
                titleId = safeTitle,
                gamePath = gamePath,
                manifestKey = manifestKey,
                batchSize = resumedBatchSize,
                attemptId = logicalJobId.toString(),
                logicalSessionId = logicalJobId.toString(),
            )
            PpuInstallSessionStore.save(appContext, session)

            var totalModules = session.totalModules
            var completedModules = session.completedModules
            var batchIndex = session.batchIndex
            var batchSize = session.batchSize
            var failuresAtMinimum = 0
            var bindFailures = 0
            val noProgress = NoProgressGuard(PpuWorkerControlPolicy.ZERO_PROGRESS_BATCH_LIMIT)
            val user = runCatching { UserRepository.getUserFromSettings() }.getOrDefault("00000001")

            PpuReadinessStore.setRuntimeState(appContext, safeTitle, RuntimePpuState.COMPILING)
            updateProgress(appContext, safeTitle, logicalJobId, completedModules, totalModules, active = true)
            Log.i("S3PPUSESSION", "origin=PRELAUNCH session=$sessionId title=$safeTitle state=START batch=$batchIndex")

            while (!canceled) {
                val resultDeferred = CompletableDeferred<JSONObject>()
                val exitDeferred = CompletableDeferred<WorkerDeathReason>()
                var connection: PpuBatchWorkerConnection? = null
                connection = PpuBatchWorkerConnection(appContext) { reason ->
                    if (reason !is WorkerDeathReason.ExpectedAfterResult) {
                        resultDeferred.complete(
                            JSONObject().put("status", "unexpected_death").put("message", "worker_died")
                        )
                    }
                    exitDeferred.complete(reason)
                }

                if (!connection.bind()) {
                    connection.releaseBindingAfterExit()
                    bindFailures++
                    if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                        markFailed(appContext, safeTitle, logicalJobId, "Could not start PPU worker")
                        return@withContext false
                    }
                    delay(300)
                    continue
                }

                activeConnection = connection
                val worker = try {
                    val ready = withTimeoutOrNull(PpuWorkerControlPolicy.STARTUP_TIMEOUT_MS) {
                        connection.connectionReady.await()
                    }
                    if (ready == null) {
                        awaitVerifiedExitOrQuarantine(
                            connection, appContext, safeTitle, logicalJobId,
                            signalImmediately = true,
                            reason = "Worker startup timeout",
                        )
                        connection.releaseBindingAfterExit()
                        if (activeConnection === connection) activeConnection = null
                        bindFailures++
                        if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                            markFailed(appContext, safeTitle, logicalJobId, "Worker startup timeout")
                            return@withContext false
                        }
                        delay(300)
                        continue
                    }
                    ready
                } catch (e: CancellationException) {
                    canceled = true
                    withContext(NonCancellable) {
                        awaitVerifiedExitOrQuarantine(
                            connection, appContext, safeTitle, logicalJobId,
                            signalImmediately = false,
                            reason = "Worker exit was not observed after cancellation",
                        )
                        if (activeConnection === connection) activeConnection = null
                        connection.releaseBindingAfterExit()
                        markCanceled(appContext, safeTitle, logicalJobId)
                    }
                    throw e
                } catch (e: Exception) {
                    connection.releaseBindingAfterExit()
                    bindFailures++
                    if (bindFailures >= PpuWorkerControlPolicy.BIND_RETRY_LIMIT) {
                        markFailed(appContext, safeTitle, logicalJobId, e.message ?: "Worker connection failed")
                        return@withContext false
                    }
                    delay(300)
                    continue
                }
                bindFailures = 0
                runCatching {
                    val pid = worker.workerPid
                    if (pid > 0) connection.workerPid = pid
                }
                if (canceled) {
                    connection.requestCancel(sessionId)
                    awaitVerifiedExitOrQuarantine(
                        connection, appContext, safeTitle, logicalJobId,
                        signalImmediately = false,
                        reason = "Worker exit was not observed after cancellation",
                    )
                    connection.releaseBindingAfterExit()
                    markCanceled(appContext, safeTitle, logicalJobId)
                    return@withContext false
                }

                val callback = object : IPpuBatchCallback.Stub() {
                    override fun onBatchStarted(
                        cbSessionId: Long,
                        workerPid: Int,
                        workerInstanceId: String?,
                        cbBatchIndex: Int,
                    ) {
                        if (cbSessionId != sessionId || cbBatchIndex != batchIndex) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "started", "phase" to "PRELAUNCH"))
                            return
                        }
                        if (workerPid > 0) connection.workerPid = workerPid
                        connection.workerInstanceId = workerInstanceId
                        Log.i("S3PPUBATCH", "origin=PRELAUNCH batch=$cbBatchIndex pid=$workerPid state=STARTED")
                    }

                    override fun onProgress(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbTotal: Int,
                        cbCompleted: Int,
                        message: String?,
                    ) {
                        if (canceled || cbSessionId != sessionId || cbJobId != logicalJobId) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "progress", "phase" to "PRELAUNCH"))
                            return
                        }
                        updateProgress(
                            appContext,
                            safeTitle,
                            logicalJobId,
                            maxOf(completedModules, cbCompleted),
                            maxOf(totalModules, cbTotal),
                            if (maxOf(totalModules, cbTotal) > 0) maxOf(completedModules, cbCompleted) * 100 / maxOf(totalModules, cbTotal) else 0,
                            message.takeIf { cbTotal <= 0 },
                            active = true,
                        )
                    }

                    override fun onBatchFinished(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbBatchIndex: Int,
                        resultJson: String?,
                    ) {
                        if (cbSessionId != sessionId || cbJobId != logicalJobId || cbBatchIndex != batchIndex) {
                            PpuDiagnosticLog.emit("stale_callback_ignored", titleId = safeTitle, extras = mapOf("type" to "finished", "phase" to "PRELAUNCH"))
                            return
                        }
                        connection.isResultReceived = true
                        resultDeferred.complete(
                            runCatching { JSONObject(resultJson ?: "{}") }.getOrElse {
                                JSONObject().put("status", "failed").put("message", it.message)
                            }
                        )
                    }
                }

                session = session.copy(
                    batchIndex = batchIndex,
                    batchSize = batchSize,
                    phase = PpuSessionPhase.BATCH_RUNNING,
                    updatedMs = System.currentTimeMillis(),
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
                        batchSize,
                        RPCSX.COMPILE_ORIGIN_PRELAUNCH,
                        manifestKey,
                        callback,
                    )
                } catch (e: Exception) {
                    resultDeferred.complete(
                        JSONObject().put("status", "failed").put("message", e.message)
                    )
                }

                val result = try {
                    resultDeferred.await()
                } catch (e: CancellationException) {
                    canceled = true
                    withContext(NonCancellable) {
                        connection.requestCancel(sessionId)
                        awaitVerifiedExitOrQuarantine(
                            connection, appContext, safeTitle, logicalJobId,
                            signalImmediately = false,
                            reason = "Worker exit was not observed after cancellation",
                        )
                        connection.releaseBindingAfterExit()
                        markCanceled(appContext, safeTitle, logicalJobId)
                    }
                    throw e
                }
                val death = withTimeoutOrNull(PpuWorkerControlPolicy.EXIT_GRACE_MS) {
                    exitDeferred.await()
                } ?: awaitVerifiedExitOrQuarantine(
                    connection, appContext, safeTitle, logicalJobId,
                    signalImmediately = true,
                    reason = "Worker exit was not observed after result",
                )
                connection.releaseBindingAfterExit()
                if (activeConnection === connection) activeConnection = null

                val report = PpuNativeBatchReport.parse(result.toString())
                if (report.status == "all_complete" && !report.provesCompletion) {
                    markFailed(appContext, safeTitle, logicalJobId, "Native completion receipt was not auditable")
                    return@withContext false
                }
                val workerTotal = report.totalModules
                val cachedAfter = report.cachedAfter
                val completedBefore = completedModules
                val totalBefore = totalModules
                if (workerTotal > totalModules) totalModules = workerTotal
                val reduced = PpuOverallProgressReducer.reduceBatchFinished(
                    titleTotal = totalModules,
                    lastKnownCompleted = completedModules,
                    workerTotal = workerTotal,
                    cachedAfter = cachedAfter,
                )
                completedModules = maxOf(completedModules, reduced.completedModules)
                totalModules = maxOf(totalModules, reduced.totalModules)
                updateProgress(
                    appContext,
                    safeTitle,
                    logicalJobId,
                    completedModules,
                    reduced.totalModules,
                    reduced.percent,
                    active = true,
                )

                val status = report.status
                session = session.copy(
                    totalModules = totalModules,
                    completedModules = completedModules,
                    phase = if (status == "all_complete") PpuSessionPhase.COMPLETED else PpuSessionPhase.MORE_WORK,
                    updatedMs = System.currentTimeMillis(),
                )
                PpuInstallSessionStore.save(appContext, session)

                if (canceled || status == "canceled") {
                    markCanceled(appContext, safeTitle, logicalJobId)
                    return@withContext false
                }

                if (death !is WorkerDeathReason.ExpectedAfterResult) {
                    val oldSize = batchSize
                    batchSize = PpuBatchPolicy.nextBatchSizeOnFailure(batchSize)
                    if (oldSize == PpuBatchPolicy.MIN_BATCH_SIZE) failuresAtMinimum++ else failuresAtMinimum = 0
                    if (PpuBatchPolicy.shouldFailPermanently(failuresAtMinimum)) {
                        markFailed(appContext, safeTitle, logicalJobId, "Repeated PPU worker crash")
                        return@withContext false
                    }
                    delay(300)
                    continue
                }

                failuresAtMinimum = 0
                when (status) {
                    "all_complete" -> {
                        PpuInstallSessionStore.clear(appContext, PpuBatchKind.RUNTIME)
                        markCompleted(appContext, safeTitle, logicalJobId, totalModules)
                        return@withContext true
                    }
                    "more_work" -> {
                        val newly = (completedModules - completedBefore).coerceAtLeast(0)
                        val discoveryAdvanced = totalModules > totalBefore
                        if (noProgress.completedBatch(newly.toLong(), discoveryAdvanced || newly > 0)) {
                            markFailed(appContext, safeTitle, logicalJobId, "Repeated zero-progress batches")
                            return@withContext false
                        }
                        batchIndex++
                        delay(150)
                    }
                    "canceled" -> {
                        markCanceled(appContext, safeTitle, logicalJobId)
                        return@withContext false
                    }
                    else -> {
                        markFailed(
                            appContext,
                            safeTitle,
                            logicalJobId,
                            result.optString("message", "Runtime PPU batch failed"),
                        )
                        return@withContext false
                    }
                }
            }

            markCanceled(appContext, safeTitle, logicalJobId)
            false
        } catch (e: CancellationException) {
            canceled = true
            markCanceled(appContext, safeTitle, logicalJobId)
            throw e
        } finally {
            activeSessionId = null
            activeLogicalJobId = null
            activeConnection = null
            acquiredLock.close()
        }
    }

    private fun updateProgress(
        context: Context,
        titleId: String,
        jobId: Long,
        done: Int,
        total: Int,
        percent: Int = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0,
        message: String? = null,
        active: Boolean,
        outcome: CompileOutcome = CompileOutcome.NONE,
    ) {
        val cur = CompileProgressBridge.prelaunchState.value
        val merged = if (active && cur.ppuActive &&
            (cur.titleId.isNullOrBlank() || cur.titleId.equals(titleId, ignoreCase = true))
        ) {
            PpuOverallProgressReducer.mergeMonotonic(
                OverallProgress(cur.moduleTotal, cur.moduleDone, cur.ppuPercent),
                OverallProgress(total, done, percent),
            )
        } else {
            OverallProgress(total, done, percent)
        }
        val moduleMsg = message ?: if (merged.totalModules > 0) {
            "module ${merged.completedModules} of ${merged.totalModules}"
        } else {
            "Preparing Runtime PPU"
        }
        val remaining = if (active) {
            PpuRemainingTimeTracker.observeRuntime(
                titleId, merged.completedModules, merged.totalModules, active = true,
            )
        } else {
            PpuRemainingTimeTracker.resetRuntime()
            null
        }
        CompileProgressBridge.updatePrelaunchStateForExternalWorker(
            context = context,
            titleId = titleId,
            jobId = jobId,
            moduleDone = merged.completedModules,
            moduleTotal = merged.totalModules,
            percent = merged.percent,
            message = moduleMsg,
            active = active,
            outcome = outcome,
            remainingLabel = remaining,
        )
    }

    private fun markCompleted(context: Context, titleId: String, jobId: Long, total: Int) {
        updateProgress(
            context, titleId, jobId, total, total, 100,
            "Runtime PPU preparation complete", false, CompileOutcome.COMPLETED,
        )
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.IDLE_AFTER_COMPILE)
        Log.i("S3PPUSESSION", "origin=PRELAUNCH state=FINAL_COMPLETED title=$titleId job=$jobId total=$total")
    }

    private fun markFailed(context: Context, titleId: String, jobId: Long, reason: String) {
        updateProgress(
            context, titleId, jobId, 0, 0, 0,
            "Runtime PPU failed: $reason", false, CompileOutcome.FAILED,
        )
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
    }

    private fun markCanceled(context: Context, titleId: String, jobId: Long) {
        updateProgress(
            context, titleId, jobId, 0, 0, 0,
            "Runtime PPU stopped — retry to resume", false, CompileOutcome.CANCELED,
        )
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
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

        PpuDiagnosticLog.emit(
            "stop_failed_keep_lease",
            titleId = titleId,
            pid = connection.workerPid,
            phase = "PRELAUNCH",
        )
        markFailed(context, titleId, jobId, reason)
        ImportPpuPreparationCoordinator.onWorkerExitUnverified(titleId, jobId)
        return connection.awaitVerifiedExit()
    }
}
