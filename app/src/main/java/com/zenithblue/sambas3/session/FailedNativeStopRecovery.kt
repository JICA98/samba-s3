package com.zenithblue.sambas3.session

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.zenithblue.sambas3.MainActivity
import com.zenithblue.sambas3.logging.CaptureState
import com.zenithblue.sambas3.logging.LogBroker
import com.zenithblue.sambas3.logging.LogSessionStore
import com.zenithblue.sambas3.logging.LogSessionTerminal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recovers from a native core which ignored every bounded stop attempt.
 *
 * Returning to MainActivity inside the same process is unsafe: the old core
 * can retain runaway CPU/SPU threads and global RPCSX state. Schedule a fresh
 * launcher task first, then terminate only this app process. The session
 * journal is marked failed by [EmulatorStopCoordinator] before this runs.
 */
object FailedNativeStopRecovery {
    private const val TAG = "S3STOP"
    private const val RESTART_REQUEST_CODE = 0x5333
    private const val KILL_DELAY_MS = 300L
    private const val RESTART_DELAY_MS = 900L
    private val scheduled = AtomicBoolean(false)

    fun schedule(context: Context, requestId: Long, reason: EmulatorStopReason) {
        scheduleCleanProcessRestart(
            context = context,
            requestId = requestId,
            reason = reason,
            detail = "native stop timed out",
        )
    }

    /**
     * A no-frame timeout means the core has already failed. Calling its stop
     * path can make every live ARM SPU thread jump into invalid host memory,
     * turning the typed timeout into a SIGSEGV before Home can present it.
     * The caller persists crash evidence before entering this method.
     */
    fun scheduleAfterFrameTimeout(context: Context, evidence: String) {
        scheduleCleanProcessRestart(
            context = context,
            requestId = System.currentTimeMillis(),
            reason = EmulatorStopReason.CrashExit,
            detail = evidence,
        )
    }

    private fun scheduleCleanProcessRestart(
        context: Context,
        requestId: Long,
        reason: EmulatorStopReason,
        detail: String,
    ) {
        if (!scheduled.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        EmulationSessionJournal.read(appContext)?.let { session ->
            runCatching { LogBroker.snapshotNativeBeforeReopen(appContext) }
                .onFailure { Log.w(TAG, "id=$requestId native log snapshot unavailable", it) }
            runCatching {
                // Native writers cannot be safely drained before this forced restart.
                LogSessionStore.finalize(
                    appContext, session.sessionId, LogSessionTerminal.FAILED, detail,
                    captureState = CaptureState.RECOVERED_PARTIAL,
                )
            }.onFailure { error ->
                Log.e(TAG, "id=$requestId failed to finalize crash logs", error)
            }
        }
        val restartIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("native_stop_recovery", true)
            putExtra("native_stop_request_id", requestId)
            putExtra("native_stop_reason", reason.name)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        runCatching {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pendingIntent,
            )
        }.onFailure { error ->
            scheduled.set(false)
            Log.e(TAG, "id=$requestId failed to schedule clean-process recovery", error)
            return
        }

        Log.e(
            TAG,
            "id=$requestId clean-process recovery reason=${reason.name} detail=$detail; " +
                "scheduled clean-process recovery and terminating pid=${Process.myPid()}",
        )
        Handler(Looper.getMainLooper()).postDelayed(
            { Process.killProcess(Process.myPid()) },
            KILL_DELAY_MS,
        )
    }
}
