package com.zenithblue.sambas3.ppu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

sealed class WorkerDeathReason {
    object ExpectedAfterResult : WorkerDeathReason()
    data class Unexpected(val lastWorkerPid: Int?, val lastInstanceId: String?) : WorkerDeathReason()
}

class PpuBatchWorkerConnection(
    private val context: Context,
    private val onDeath: (WorkerDeathReason) -> Unit
) : ServiceConnection {
    companion object {
        private const val TAG = "PpuWorkerConn"
    }

    private var bound = false
    private var serviceBinder: IBinder? = null
    var worker: IPpuBatchWorker? = null
        private set
    var isResultReceived = false
    @Volatile
    var workerPid: Int? = null

    val connectionReady = CompletableDeferred<IPpuBatchWorker>()
    private val deathNotified = AtomicBoolean(false)

    private val deathRecipient = IBinder.DeathRecipient {
        Log.i("S3PPUIPC", "worker_death_recipient_fired expected=$isResultReceived")
        val reason = if (isResultReceived) {
            WorkerDeathReason.ExpectedAfterResult
        } else {
            val pid = workerPid ?: runCatching { worker?.workerPid }.getOrNull()
            val inst = runCatching { worker?.workerInstanceId }.getOrNull()
            WorkerDeathReason.Unexpected(pid, inst)
        }
        cleanup()
        notifyDeath(reason)
    }

    fun bind(): Boolean {
        val intent = Intent(context, PpuBatchWorkerService::class.java)
        bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        return bound
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serviceBinder = service
        try {
            service?.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            Log.w(TAG, "linkToDeath failed: ${e.message}")
        }
        val w = IPpuBatchWorker.Stub.asInterface(service)
        worker = w
        connectionReady.complete(w)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        worker = null
    }

    fun unbind() {
        cleanup()
    }

    /**
     * Unbind first so BIND_AUTO_CREATE cannot respawn `:ppu_compile`, then
     * SIGKILL. Completes [onDeath] so the orchestrator cannot hang in await.
     */
    fun killWorkerProcess() {
        val pid = workerPid ?: runCatching { worker?.workerPid }.getOrNull()
        workerPid = null
        val expected = isResultReceived
        cleanup()
        if (!connectionReady.isCompleted) connectionReady.cancel()
        PpuWorkerProcessKiller.kill(context, pid)
        notifyDeath(
            if (expected) {
                WorkerDeathReason.ExpectedAfterResult
            } else {
                WorkerDeathReason.Unexpected(pid, null)
            }
        )
    }

    private fun notifyDeath(reason: WorkerDeathReason) {
        if (deathNotified.compareAndSet(false, true)) {
            onDeath(reason)
        }
    }

    private fun cleanup() {
        try {
            serviceBinder?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Exception) {}
        serviceBinder = null
        worker = null
        if (bound) {
            bound = false
            try {
                context.unbindService(this)
            } catch (_: Exception) {}
        }
    }
}
