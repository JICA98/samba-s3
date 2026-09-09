package com.zenithblue.sambas3.ppu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

sealed class WorkerDeathReason {
    object ExpectedAfterResult : WorkerDeathReason()
    data class Unexpected(val lastWorkerPid: Int?, val lastInstanceId: String?) : WorkerDeathReason()
    object NullBinding : WorkerDeathReason()
    object BindingDied : WorkerDeathReason()
}

class PpuBatchWorkerConnection(
    context: Context,
    private val onDeath: (WorkerDeathReason) -> Unit,
) : ServiceConnection {
    companion object {
        private const val TAG = "PpuWorkerConn"
    }

    private val context = context.applicationContext

    private var bound = false
    private var serviceBinder: IBinder? = null
    var worker: IPpuBatchWorker? = null
        private set
    var isResultReceived = false
    @Volatile
    var workerPid: Int? = null
    @Volatile
    var workerInstanceId: String? = null

    val connectionReady = CompletableDeferred<IPpuBatchWorker>()
    val verifiedExit = CompletableDeferred<WorkerDeathReason>()
    private val deathNotified = AtomicBoolean(false)
    private val deathLinked = AtomicBoolean(false)

    private val deathRecipient = IBinder.DeathRecipient {
        Log.i("S3PPUIPC", "worker_death_recipient_fired expected=$isResultReceived pid=$workerPid inst=$workerInstanceId")
        val reason = if (isResultReceived) {
            WorkerDeathReason.ExpectedAfterResult
        } else {
            WorkerDeathReason.Unexpected(workerPid, workerInstanceId)
        }
        completeVerifiedExit(reason)
    }

    fun bind(): Boolean {
        val intent = Intent(context, PpuBatchWorkerService::class.java)
        val started = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            false
        }
        if (!started) {
            PpuDiagnosticLog.emit(
                "worker_bind",
                extras = mapOf("foregroundStart" to false, "bound" to false),
            )
            return false
        }
        bound = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed: ${e.message}", e)
            false
        }
        if (!bound) {
            runCatching { context.stopService(intent) }
        }
        PpuDiagnosticLog.emit(
            "worker_bind",
            extras = mapOf("foregroundStart" to started, "bound" to bound),
        )
        return bound
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        serviceBinder = service
        try {
            service?.linkToDeath(deathRecipient, 0)
            deathLinked.set(true)
        } catch (e: Exception) {
            Log.w(TAG, "linkToDeath failed: ${e.message}")
            connectionReady.completeExceptionally(IllegalStateException("link_to_death_failed", e))
            return
        }
        val w = IPpuBatchWorker.Stub.asInterface(service)
        worker = w
        runCatching {
            val pid = w.workerPid
            if (pid > 0) workerPid = pid
            workerInstanceId = w.workerInstanceId
        }
        PpuDiagnosticLog.emit(
            "worker_connected",
            workerInstanceId = workerInstanceId,
            pid = workerPid,
        )
        connectionReady.complete(w)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        worker = null
    }

    override fun onNullBinding(name: ComponentName?) {
        PpuDiagnosticLog.emit("worker_null_binding")
        if (!connectionReady.isCompleted) {
            connectionReady.completeExceptionally(IllegalStateException("null_binding"))
        }
        completeVerifiedExit(WorkerDeathReason.NullBinding)
    }

    override fun onBindingDied(name: ComponentName?) {
        PpuDiagnosticLog.emit("worker_binding_died", pid = workerPid, workerInstanceId = workerInstanceId)
        if (!connectionReady.isCompleted) {
            connectionReady.completeExceptionally(IllegalStateException("binding_died"))
        }
        completeVerifiedExit(WorkerDeathReason.BindingDied)
    }

    fun requestCancel(sessionId: Long) {
        val w = worker
        PpuDiagnosticLog.emit("cancel_requested", extras = mapOf("sessionId" to sessionId, "pid" to workerPid))
        Thread {
            runCatching { w?.cancel(sessionId) }
        }.start()
    }

    /**
     * Unbind so BIND_AUTO_CREATE cannot respawn the worker, keep death observation,
     * then SIGKILL only the known pid. Does not synthesize a death event.
     */
    fun signalVerifiedInstance() {
        val pid = workerPid
        val inst = workerInstanceId
        PpuDiagnosticLog.emit("signal_verified_instance", pid = pid, workerInstanceId = inst)
        preventRecreation()
        PpuWorkerProcessKiller.killKnownPid(pid)
    }

    suspend fun awaitVerifiedExit(): WorkerDeathReason = verifiedExit.await()

    fun releaseBindingAfterExit() {
        unlinkDeath()
        preventRecreation()
        serviceBinder = null
        worker = null
    }

    fun unbind() {
        releaseBindingAfterExit()
    }

    @Deprecated("Do not synthesize death from a kill request; use signalVerifiedInstance + awaitVerifiedExit")
    fun killWorkerProcess() {
        signalVerifiedInstance()
    }

    private fun preventRecreation() {
        if (bound) {
            bound = false
            try {
                context.unbindService(this)
            } catch (_: Exception) {}
        }
        // The worker is both started (to obtain real foreground-service
        // priority) and bound (for IPC). Always release the started lifetime
        // alongside the binding so failed/abandoned handshakes cannot leave a
        // notification or an idle isolated process behind.
        runCatching {
            context.stopService(Intent(context, PpuBatchWorkerService::class.java))
        }
    }

    private fun unlinkDeath() {
        if (deathLinked.compareAndSet(true, false)) {
            try {
                serviceBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
        }
    }

    private fun completeVerifiedExit(reason: WorkerDeathReason) {
        if (deathNotified.compareAndSet(false, true)) {
            verifiedExit.complete(reason)
            onDeath(reason)
            PpuDiagnosticLog.emit(
                "worker_exit_observed",
                pid = workerPid,
                workerInstanceId = workerInstanceId,
                reason = reason.toString(),
            )
        }
    }
}
