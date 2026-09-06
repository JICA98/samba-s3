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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private var activeConnection: PpuBatchWorkerConnection? = null

    @Volatile
    private var canceled = false

    fun cancel(sessionId: Long) {
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
        conn?.killWorkerProcess()
        if (context != null) {
            PpuWorkerProcessKiller.kill(context, conn?.workerPid)
        }
        Thread {
            runCatching { conn?.worker?.cancel(sid) }
        }.start()
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

            val sessionId = loaded?.sessionId ?: System.currentTimeMillis()
            activeSessionId = sessionId
            var session = loaded ?: PpuInstallSession(
                kind = PpuBatchKind.RUNTIME,
                sessionId = sessionId,
                jobId = logicalJobId,
                titleId = safeTitle,
                gamePath = gamePath,
                manifestKey = manifestKey,
                batchSize = PpuBatchPolicy.DEFAULT_BATCH_SIZE,
            )
            PpuInstallSessionStore.save(appContext, session)

            var totalModules = session.totalModules
            var completedModules = session.completedModules
            var batchIndex = session.batchIndex
            var batchSize = session.batchSize
            var failuresAtMinimum = 0
            var bindFailures = 0
            val nativeWindow = PpuNativeProgressWindow()
            val user = runCatching { UserRepository.getUserFromSettings() }.getOrDefault("00000001")

            PpuReadinessStore.setRuntimeState(appContext, safeTitle, RuntimePpuState.COMPILING)
            updateProgress(appContext, safeTitle, logicalJobId, completedModules, totalModules, active = true)
            Log.i("S3PPUSESSION", "origin=PRELAUNCH session=$sessionId title=$safeTitle state=START batch=$batchIndex")

            while (!canceled) {
                nativeWindow.reset()
                val resultDeferred = CompletableDeferred<JSONObject>()
                val exitDeferred = CompletableDeferred<WorkerDeathReason>()
                var connection: PpuBatchWorkerConnection? = null
                connection = PpuBatchWorkerConnection(appContext) { reason ->
                    if (reason is WorkerDeathReason.Unexpected) {
                        resultDeferred.complete(
                            JSONObject().put("status", "unexpected_death").put("message", "worker_died")
                        )
                    }
                    exitDeferred.complete(reason)
                }

                if (!connection.bind()) {
                    connection.unbind()
                    bindFailures++
                    if (bindFailures >= 3) {
                        markFailed(appContext, safeTitle, logicalJobId, "Could not start PPU worker")
                        return@withContext false
                    }
                    delay(300)
                    continue
                }

                activeConnection = connection
                val worker = try {
                    connection.connectionReady.await()
                } catch (e: CancellationException) {
                    canceled = true
                    connection.killWorkerProcess()
                    if (activeConnection === connection) activeConnection = null
                    markCanceled(appContext, safeTitle, logicalJobId)
                    throw e
                } catch (e: Exception) {
                    connection.unbind()
                    bindFailures++
                    if (bindFailures >= 3) {
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
                    connection.killWorkerProcess()
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
                        if (workerPid > 0) connection.workerPid = workerPid
                        Log.i("S3PPUBATCH", "origin=PRELAUNCH batch=$cbBatchIndex pid=$workerPid state=STARTED")
                    }

                    override fun onProgress(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbTotal: Int,
                        cbCompleted: Int,
                        message: String?,
                    ) {
                        if (canceled) return
                        val (absDone, absTotal) = nativeWindow.absorb(cbCompleted, cbTotal)
                        if (absTotal > totalModules) totalModules = absTotal
                        val progress = PpuOverallProgressReducer.reduceLiveProgress(
                            titleTotal = totalModules,
                            lastKnownCompleted = completedModules,
                            workerTotal = absTotal,
                            cachedBefore = completedModules,
                            currentBatchCompiled = (absDone - completedModules).coerceAtLeast(0),
                        )
                        completedModules = maxOf(completedModules, progress.completedModules)
                        totalModules = maxOf(totalModules, progress.totalModules)
                        val shown = PpuOverallProgressReducer.liveDisplay(progress)
                        updateProgress(
                            appContext,
                            safeTitle,
                            logicalJobId,
                            shown.completedModules,
                            shown.totalModules,
                            shown.percent,
                            active = true,
                        )
                    }

                    override fun onBatchFinished(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbBatchIndex: Int,
                        resultJson: String?,
                    ) {
                        connection?.isResultReceived = true
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
                    connection.killWorkerProcess()
                    markCanceled(appContext, safeTitle, logicalJobId)
                    throw e
                } finally {
                    if (activeConnection === connection) activeConnection = null
                }
                val death = exitDeferred.await()
                connection.unbind()

                val workerTotal = result.optInt("totalModules", 0)
                val cachedAfter = result.optInt("cachedAfter", 0)
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

                val status = result.optString("status", "failed")
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

                if (death is WorkerDeathReason.Unexpected) {
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
}
