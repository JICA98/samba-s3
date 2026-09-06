package com.zenithblue.sambas3.ppu

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.CompileOutcome
import com.zenithblue.sambas3.CompileProgressBridge
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.RuntimePpuState
import com.zenithblue.sambas3.UserRepository
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
    private var canceled = false

    fun cancel(sessionId: Long) {
        if (activeSessionId == sessionId) canceled = true
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
            canceled = false
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
            val user = runCatching { UserRepository.getUserFromSettings() }.getOrDefault("00000001")

            PpuReadinessStore.setRuntimeState(appContext, safeTitle, RuntimePpuState.COMPILING)
            updateProgress(appContext, safeTitle, logicalJobId, completedModules, totalModules, active = true)
            Log.i("S3PPUSESSION", "origin=PRELAUNCH session=$sessionId title=$safeTitle state=START batch=$batchIndex")

            while (!canceled) {
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

                val worker = try {
                    connection.connectionReady.await()
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

                val callback = object : IPpuBatchCallback.Stub() {
                    override fun onBatchStarted(
                        cbSessionId: Long,
                        workerPid: Int,
                        workerInstanceId: String?,
                        cbBatchIndex: Int,
                    ) {
                        Log.i("S3PPUBATCH", "origin=PRELAUNCH batch=$cbBatchIndex pid=$workerPid state=STARTED")
                    }

                    override fun onProgress(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbTotal: Int,
                        cbCompleted: Int,
                        message: String?,
                    ) {
                        if (cbTotal > 0 && totalModules == 0) totalModules = cbTotal
                        val progress = PpuOverallProgressReducer.reduceLiveProgress(
                            titleTotal = totalModules,
                            lastKnownCompleted = completedModules,
                            workerTotal = cbTotal,
                            cachedBefore = completedModules,
                            // Native moduleDone is absolute (cached + newly compiled), not a delta.
                            currentBatchCompiled = (cbCompleted - completedModules).coerceAtLeast(0),
                        )
                        updateProgress(
                            appContext,
                            safeTitle,
                            logicalJobId,
                            progress.completedModules,
                            progress.totalModules,
                            progress.percent,
                            message,
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

                val result = resultDeferred.await()
                val death = exitDeferred.await()
                connection.unbind()

                val workerTotal = result.optInt("totalModules", 0)
                val cachedAfter = result.optInt("cachedAfter", 0)
                if (workerTotal > 0) totalModules = workerTotal
                val reduced = PpuOverallProgressReducer.reduceBatchFinished(
                    titleTotal = totalModules,
                    lastKnownCompleted = completedModules,
                    workerTotal = workerTotal,
                    cachedAfter = cachedAfter,
                )
                completedModules = reduced.completedModules
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
        } finally {
            activeSessionId = null
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
        CompileProgressBridge.updatePrelaunchStateForExternalWorker(
            context = context,
            titleId = titleId,
            jobId = jobId,
            moduleDone = done,
            moduleTotal = total,
            percent = percent,
            message = message ?: if (total > 0) "Runtime PPU module $done of $total" else "Preparing Runtime PPU",
            active = active,
            outcome = outcome,
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
            "Runtime PPU canceled", false, CompileOutcome.CANCELED,
        )
        PpuReadinessStore.setRuntimeState(context, titleId, RuntimePpuState.FAILED)
    }
}
