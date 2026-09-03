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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

object PpuInstallOrchestrator {
    private const val TAG = "PpuOrchestrator"
    private const val NOTIF_INSTALL = 3000L
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeSessionId: Long? = null
    @Volatile
    private var isCanceled = false

    fun cancel(sessionId: Long) {
        if (activeSessionId == sessionId) {
            isCanceled = true
            Log.i(TAG, "Cancellation requested for session=$sessionId")
        }
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
            isCanceled = false
            val currentManifestKey = runCatching { RPCSX.instance.getPpuManifestKey(safeTitle) }.getOrNull() ?: ""
            var sessionLoaded = PpuInstallSessionStore.load(appContext)

            if (sessionLoaded != null && (sessionLoaded.titleId != safeTitle || sessionLoaded.manifestKey != currentManifestKey)) {
                Log.w(TAG, "Manifest or title mismatch (old=${sessionLoaded.titleId} new=$safeTitle) — resetting session")
                PpuInstallSessionStore.clear(appContext)
                sessionLoaded = null
            }

            val sessionId = sessionLoaded?.sessionId ?: System.currentTimeMillis()
            activeSessionId = sessionId

            var session: PpuInstallSession = sessionLoaded ?: run {
                val s = PpuInstallSession(
                    sessionId = sessionId,
                    jobId = logicalJobId,
                    titleId = safeTitle,
                    gamePath = gamePath,
                    manifestKey = currentManifestKey,
                    batchSize = PpuBatchPolicy.DEFAULT_BATCH_SIZE,
                    phase = PpuSessionPhase.CREATED
                )
                PpuInstallSessionStore.save(appContext, s)
                s
            }

            var totalModules = session.totalModules
            var completedModules = session.completedModules
            var batchIndex = session.batchIndex
            var currentBatchSize = session.batchSize
            var consecutiveMinFailures = 0

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
                    if (deathReason is WorkerDeathReason.Unexpected) {
                        batchFinishedDeferred.complete(
                            JSONObject().put("status", "unexpected_death").put("message", "worker_died")
                        )
                    }
                    processExitDeferred.complete(deathReason)
                }

                val bound = conn.bind()
                if (!bound) {
                    Log.e(TAG, "Failed to bind to PpuBatchWorkerService for batch=$batchIndex")
                    conn.unbind()
                    delay(1000)
                    continue
                }

                val worker = try {
                    conn.connectionReady.await()
                } catch (e: Exception) {
                    Log.e(TAG, "Worker connection await failed: ${e.message}")
                    conn.unbind()
                    delay(1000)
                    continue
                }

                val callback = object : IPpuBatchCallback.Stub() {
                    override fun onBatchStarted(
                        cbSessionId: Long,
                        workerPid: Int,
                        workerInstanceId: String?,
                        cbBatchIndex: Int
                    ) {
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
                        if (cbTotal > 0 && totalModules == 0) totalModules = cbTotal
                        val reduced = PpuOverallProgressReducer.reduceLiveProgress(
                            titleTotal = totalModules,
                            lastKnownCompleted = completedModules,
                            workerTotal = cbTotal,
                            cachedBefore = completedModules,
                            currentBatchCompiled = cbCompleted
                        )
                        updateProgressUi(appContext, safeTitle, logicalJobId, reduced)
                    }

                    override fun onBatchFinished(
                        cbSessionId: Long,
                        cbJobId: Long,
                        cbBatchIndex: Int,
                        resultJson: String?
                    ) {
                        conn?.let { it.isResultReceived = true }
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
                        currentManifestKey,
                        callback
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "worker.startBatch threw: ${e.message}")
                    batchFinishedDeferred.complete(
                        JSONObject().put("status", "failed").put("message", e.message)
                    )
                }

                // Live ticker to update UI per module as objects are written to disk
                val liveTicker = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive && !batchFinishedDeferred.isCompleted) {
                        delay(1000)
                        val onDisk = countCacheObjects(appContext, safeTitle)
                        if (onDisk > completedModules) {
                            val reduced = PpuOverallProgressReducer.reduceLiveProgress(
                                titleTotal = totalModules,
                                lastKnownCompleted = completedModules,
                                workerTotal = totalModules,
                                cachedBefore = completedModules,
                                currentBatchCompiled = onDisk - completedModules
                            )
                            updateProgressUi(appContext, safeTitle, logicalJobId, reduced)
                        }
                    }
                }

                // Await batch finished or unexpected termination
                val resultObj = try {
                    batchFinishedDeferred.await()
                } catch (e: Exception) {
                    JSONObject().put("status", "failed").put("message", e.message)
                } finally {
                    liveTicker.cancel()
                }

                // Await process exit
                val deathReason = processExitDeferred.await()
                conn.unbind()

                val oldWorkerDead = when (deathReason) {
                    is WorkerDeathReason.ExpectedAfterResult -> 1
                    is WorkerDeathReason.Unexpected -> 1
                }
                Log.i("S3PPUIPC", "old_worker_dead=$oldWorkerDead")

                val status = resultObj.optString("status", "failed")
                val workerTotal = resultObj.optInt("totalModules", 0)
                val cachedAfter = resultObj.optInt("cachedAfter", 0)

                if (workerTotal > 0) totalModules = workerTotal
                if (cachedAfter > completedModules) completedModules = cachedAfter

                val reduced = PpuOverallProgressReducer.reduceBatchFinished(
                    titleTotal = totalModules,
                    lastKnownCompleted = completedModules,
                    workerTotal = workerTotal,
                    cachedAfter = cachedAfter
                )
                completedModules = reduced.completedModules
                updateProgressUi(appContext, safeTitle, logicalJobId, reduced)

                session = session.copy(
                    totalModules = totalModules,
                    completedModules = completedModules,
                    phase = if (status == "all_complete") PpuSessionPhase.COMPLETED else PpuSessionPhase.MORE_WORK,
                    updatedMs = System.currentTimeMillis()
                )
                PpuInstallSessionStore.save(appContext, session)

                if (deathReason is WorkerDeathReason.Unexpected) {
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
                    Log.w(TAG, "Worker died unexpectedly pid=${deathReason.lastWorkerPid} inst=${deathReason.lastInstanceId}")
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
                        batchIndex++
                        delay(200) // Small pause between processes
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
        } finally {
            activeSessionId = null
            fileLock.close()
        }
    }

    private fun updateProgressUi(context: Context, titleId: String, jobId: Long, progress: OverallProgress) {
        val total = progress.totalModules
        val done = progress.completedModules
        val msg = if (total > 0) "module $done of $total" else "module $done"

        Log.i("S3PPUPROG", "done=$done total=$total")

        // 1. Update notification 3000
        ProgressRepository.onProgressEvent(NOTIF_INSTALL, done.toLong(), total.toLong(), msg)

        // 2. Update CompileProgressBridge.installState
        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = done,
            moduleTotal = total,
            percent = progress.percent,
            message = msg,
            active = true
        )

        // 3. Update ImportSessionStore
        ImportSessionStore.updatePhase(NOTIF_INSTALL, ImportPhase.COMPILING_PPU, resolvedTitleId = titleId)
    }

    private fun markCompleted(context: Context, titleId: String, jobId: Long, total: Int) {
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
            ImportSessionStore.updatePhase(NOTIF_INSTALL, ImportPhase.READY, resolvedTitleId = titleId)
            mainHandler.postDelayed({ ImportSessionStore.remove(NOTIF_INSTALL) }, 1200)
            ImportPpuPreparationCoordinator.onInstallPpuSuccess(context, titleId)
        }
    }

    private fun markFailed(context: Context, titleId: String, jobId: Long, reason: String) {
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
        CompileProgressBridge.updateInstallStateForExternalWorker(
            titleId = titleId,
            jobId = jobId,
            moduleDone = 0,
            moduleTotal = 0,
            percent = 0,
            message = "PPU compilation canceled",
            active = false,
            outcome = CompileOutcome.CANCELED
        )
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
                    total += (sub.list()?.size ?: 0)
                } else if (sub.name.endsWith(".obj") || sub.name.endsWith(".obj.gz")) {
                    total++
                }
            }
            total
        } catch (_: Exception) {
            0
        }
    }
}
