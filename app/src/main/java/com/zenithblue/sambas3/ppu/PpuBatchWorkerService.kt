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
import kotlin.concurrent.thread

class PpuBatchWorkerService : Service() {
    companion object {
        private const val TAG = "PpuBatchWorker"
    }

    private val serviceInstanceId = UUID.randomUUID().toString()
    private val processStartTimeMs = System.currentTimeMillis()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : IPpuBatchWorker.Stub() {
        override fun startBatch(
            logicalSessionId: Long,
            logicalJobId: Long,
            titleId: String?,
            gamePath: String?,
            userId: String?,
            batchIndex: Int,
            maxNewObjects: Int,
            manifestKey: String?,
            callback: IPpuBatchCallback?
        ) {
            val myPid = Process.myPid()
            Log.i(
                "S3PPUBATCH",
                "batch=$batchIndex pid=$myPid worker=$serviceInstanceId state=START " +
                    "session=$logicalSessionId job=$logicalJobId title=$titleId maxNew=$maxNewObjects"
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

                // Forward native progress to Binder callback
                try {
                    RPCSX.instance.setCompileProgressListener { domain, phase, origin, jobId, value, max, message, evtTitleId, fileDone, fileTotal, moduleDone, moduleTotal ->
                        try {
                            if (phase == RPCSX.COMPILE_PHASE_PROGRESS) {
                                callback?.onProgress(
                                    logicalSessionId,
                                    logicalJobId,
                                    moduleTotal,
                                    moduleDone,
                                    message ?: "Compiling"
                                )
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setCompileProgressListener in worker: ${e.message}")
                }

                val safeTitle = titleId ?: ""
                val safePath = gamePath ?: ""
                val resultJson = try {
                    RPCSX.instance.compileInstallPpuBatch(
                        safeTitle,
                        safePath,
                        logicalJobId,
                        maxNewObjects
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "compileInstallPpuBatch threw: ${e.message}", e)
                    """{"status":"failed","message":"${e.message}"}"""
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
