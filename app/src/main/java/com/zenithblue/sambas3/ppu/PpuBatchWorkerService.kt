package com.zenithblue.sambas3.ppu

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class PpuBatchWorkerService : Service() {
    companion object {
        private const val TAG = "PpuBatchWorker"
    }

    private val serviceInstanceId = UUID.randomUUID().toString()
    private val processStartTimeMs = System.currentTimeMillis()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batchAdmitted = AtomicBoolean(false)
    private val activeLogicalSessionId = AtomicLong(0L)

    private val binder = object : IPpuBatchWorker.Stub() {
        override fun startBatch(
            logicalSessionId: Long,
            logicalJobId: Long,
            titleId: String?,
            gamePath: String?,
            userId: String?,
            batchIndex: Int,
            maxNewObjects: Int,
            compileOrigin: Int,
            manifestKey: String?,
            callback: IPpuBatchCallback?
        ) {
            val myPid = Process.myPid()
            if (!batchAdmitted.compareAndSet(false, true)) {
                Log.w(TAG, "duplicate startBatch rejected instance=$serviceInstanceId batch=$batchIndex")
                try {
                    callback?.onBatchFinished(
                        logicalSessionId,
                        logicalJobId,
                        batchIndex,
                        """{"status":"failed","message":"duplicate_start"}""",
                    )
                } catch (_: Exception) {}
                return
            }
            if (logicalSessionId <= 0L) {
                callback?.onBatchFinished(
                    logicalSessionId,
                    logicalJobId,
                    batchIndex,
                    """{"status":"failed","message":"invalid_session"}""",
                )
                scheduleExit(myPid)
                return
            }
            activeLogicalSessionId.set(logicalSessionId)
            val workerEpoch = "worker-$serviceInstanceId-$logicalSessionId-$batchIndex"
            Log.i(
                "S3PPUBATCH",
                "batch=$batchIndex pid=$myPid worker=$serviceInstanceId epoch=$workerEpoch state=START " +
                    "session=$logicalSessionId job=$logicalJobId title=$titleId maxNew=$maxNewObjects origin=$compileOrigin"
            )
            PpuDiagnosticLog.emit(
                "batch_start",
                titleId = titleId,
                extras = mapOf(
                    "sessionId" to logicalSessionId,
                    "batchIndex" to batchIndex,
                    "origin" to compileOrigin,
                    "pid" to myPid,
                    "workerInstanceId" to serviceInstanceId,
                ),
            )

            thread(name = "ppu-batch-worker-$batchIndex") {
                val initOk = PpuWorkerNativeBootstrap.ensureInitialized(this@PpuBatchWorkerService)
                if (!initOk) {
                    Log.e(TAG, "Native bootstrap failed for batch=$batchIndex")
                    try {
                        callback?.onBatchFinished(
                            logicalSessionId,
                            logicalJobId,
                            batchIndex,
                            """{"status":"failed","message":"bootstrap_failed"}"""
                        )
                    } catch (_: Exception) {}
                    scheduleExit(myPid)
                    return@thread
                }

                try {
                    callback?.onBatchStarted(logicalSessionId, myPid, workerInstanceId, batchIndex)
                } catch (e: Exception) {
                    Log.w(TAG, "callback.onBatchStarted failed: ${e.message}")
                }

                val safeTitle = titleId ?: ""
                val safePath = gamePath ?: ""
                val requestedUser = userId?.takeIf { it.isNotBlank() } ?: "00000001"
                runCatching { RPCSX.instance.loginUser(requestedUser) }
                val actualManifest = runCatching { RPCSX.instance.getPpuManifestKey(safeTitle) }
                    .getOrNull().orEmpty()
                val requestedManifest = manifestKey.orEmpty()
                val coreId = runCatching { RPCSX.instance.getCoreBuildId() }.getOrNull().orEmpty()
                PpuDiagnosticLog.emit(
                    "worker_handshake",
                    titleId = safeTitle,
                    manifestId = actualManifest,
                    extras = mapOf(
                        "requestedManifest" to requestedManifest,
                        "user" to requestedUser,
                        "coreId" to coreId,
                    ),
                )
                if (requestedManifest.isBlank() || requestedManifest == "unknown" ||
                    actualManifest.isBlank() || actualManifest == "unknown" ||
                    !actualManifest.equals(requestedManifest, ignoreCase = true)
                ) {
                    try {
                        callback?.onBatchFinished(
                            logicalSessionId,
                            logicalJobId,
                            batchIndex,
                            """{"status":"failed","message":"manifest_mismatch"}""",
                        )
                    } catch (_: Exception) {}
                    scheduleExit(myPid)
                    return@thread
                }

                try {
                    RPCSX.instance.setCompileProgressListener { domain, phase, origin, jobId, value, max, message, evtTitleId, fileDone, fileTotal, moduleDone, moduleTotal ->
                        try {
                            if (domain != RPCSX.COMPILE_DOMAIN_PPU) return@setCompileProgressListener
                            if (origin != compileOrigin || jobId != logicalJobId) return@setCompileProgressListener
                            if (safeTitle.isNotBlank() &&
                                (evtTitleId.isNullOrBlank() || !evtTitleId.equals(safeTitle, ignoreCase = true))) return@setCompileProgressListener
                            if (phase == RPCSX.COMPILE_PHASE_PROGRESS || phase == RPCSX.COMPILE_PHASE_BEGIN) {
                                val total = if (moduleTotal > 0) moduleTotal else 0
                                callback?.onProgress(
                                    logicalSessionId,
                                    logicalJobId,
                                    total,
                                    moduleDone,
                                    message ?: "Compiling"
                                )
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setCompileProgressListener in worker: ${e.message}")
                }
                val (nativePath, releaseNativePath) = try {
                    PpuCompilePathResolver.materializeNativePath(this@PpuBatchWorkerService, safePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open compile path=$safePath: ${e.message}", e)
                    try {
                        callback?.onBatchFinished(
                            logicalSessionId,
                            logicalJobId,
                            batchIndex,
                            """{"status":"failed","message":"iso_unavailable"}"""
                        )
                    } catch (_: Exception) {}
                    scheduleExit(myPid)
                    return@thread
                }
                val resultJson = try {
                    if (compileOrigin == RPCSX.COMPILE_ORIGIN_PRELAUNCH) {
                        RPCSX.instance.compileRuntimePpuBatch(
                            safeTitle,
                            nativePath,
                            logicalJobId,
                            maxNewObjects
                        )
                    } else {
                        RPCSX.instance.compileInstallPpuBatch(
                            safeTitle,
                            nativePath,
                            logicalJobId,
                            maxNewObjects
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PPU batch threw origin=$compileOrigin: ${e.message}", e)
                    """{"status":"failed","message":"${e.message}"}"""
                } finally {
                    releaseNativePath?.invoke()
                }

                Log.i(
                    "S3PPUBATCH",
                    "batch=$batchIndex pid=$myPid worker=$serviceInstanceId state=FINISH result=$resultJson"
                )

                try {
                    callback?.onBatchFinished(logicalSessionId, logicalJobId, batchIndex, resultJson)
                } catch (e: Exception) {
                    Log.w(TAG, "callback.onBatchFinished failed: ${e.message}")
                }

                Log.i("S3PPUBATCH", "batch=$batchIndex pid=$myPid worker=$serviceInstanceId state=EXPECTED_EXIT")
                scheduleExit(myPid)
            }
        }

        override fun cancel(logicalSessionId: Long) {
            val active = activeLogicalSessionId.get()
            if (active <= 0L || logicalSessionId != active) {
                Log.w(TAG, "Ignoring stale worker cancel requested=$logicalSessionId active=$active")
                PpuDiagnosticLog.emit(
                    "stale_stop_ignored",
                    extras = mapOf("requestedSessionId" to logicalSessionId, "activeSessionId" to active),
                )
                return
            }
            Log.i(TAG, "Worker cancel requested for session=$logicalSessionId")
            try {
                RPCSX.instance.cancelInstallPpuBatch()
            } catch (e: Exception) {
                Log.w(TAG, "cancelInstallPpuBatch failed: ${e.message}")
            }
        }

        override fun getWorkerPid(): Int = Process.myPid()

        override fun getWorkerInstanceId(): String = serviceInstanceId
    }

    private fun scheduleExit(pid: Int) {
        mainHandler.postDelayed({
            try {
                Process.killProcess(pid)
            } catch (_: Exception) {}
        }, 150)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
