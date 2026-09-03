package com.zenithblue.sambas3.ppu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
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

    val connectionReady = CompletableDeferred<IPpuBatchWorker>()

    private val deathRecipient = IBinder.DeathRecipient {
        Log.i("S3PPUIPC", "worker_death_recipient_fired expected=$isResultReceived")
        val reason = if (isResultReceived) {
            WorkerDeathReason.ExpectedAfterResult
        } else {
            val pid = runCatching { worker?.workerPid }.getOrNull()
            val inst = runCatching { worker?.workerInstanceId }.getOrNull()
            WorkerDeathReason.Unexpected(pid, inst)
        }
        cleanup()
        onDeath(reason)
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
