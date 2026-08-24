package com.zenithblue.sambas3

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class CompilationMonitorService : Service() {

    companion object {
        const val NOTIF_FGS = 2000
        const val NOTIF_PPU = 2001
        const val NOTIF_SHADER = 2002
        const val TAG = "CompileMonitorService"

        fun startForEvent(context: android.content.Context, event: CompileProgressBridge.NativeEvent) {
            val intent = Intent(context, CompilationMonitorService::class.java).apply {
                putExtra("domain", event.domain)
                putExtra("phase", event.phase)
                putExtra("origin", event.origin)
                putExtra("jobId", event.jobId)
                putExtra("value", event.value)
                putExtra("max", event.max)
                putExtra("message", event.message)
                putExtra("fileDone", event.fileDone)
                putExtra("fileTotal", event.fileTotal)
                putExtra("moduleDone", event.moduleDone)
                putExtra("moduleTotal", event.moduleTotal)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var isForeground = false
    private var lastState: CompileProgressBridge.CompileState? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        // Do NOT register native listener here — bridge owns it
        collectJob = CompileProgressBridge.state
            .onEach { state -> onStateChanged(state) }
            .launchIn(serviceScope)
        Log.i(TAG, "onCreate collected state")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val domain = intent?.getIntExtra("domain", -1) ?: -1
        val phase = intent?.getIntExtra("phase", -1) ?: -1
        val origin = intent?.getIntExtra("origin", -1) ?: -1
        val jobId = intent?.getLongExtra("jobId", -1L) ?: -1L

        Log.i(TAG, "onStartCommand domain=$domain phase=$phase origin=$origin job=$jobId startId=$startId")

        // Install-origin PPU is owned by PrecompilerService. We were still started via
        // startForegroundService, so we must promote then stop to satisfy the ~5s contract.
        if (origin == RPCSX.COMPILE_ORIGIN_INSTALL) {
            Log.w(TAG, "Ignoring INSTALL-origin start request domain=$domain job=$jobId")
            return promoteThenMaybeStop(CompileProgressBridge.state.value, forceStop = true, startId = startId)
        }

        val live = CompileProgressBridge.state.value
        val intentJobActive = domain != -1 && jobId != -1L &&
            CompileProgressBridge.isRuntimeJobActive(domain, jobId)
        // Never reconstruct an active domain from a stale BEGIN extra. Live StateFlow is source of truth.
        val snapshot = if (live.isActive) live else CompileProgressBridge.CompileState()
        val forceStop = CompilationMonitorLogic.shouldStopAfterPromotion(live.activeDomainCount) && !intentJobActive
        return promoteThenMaybeStop(snapshot, forceStop = forceStop, startId = startId)
    }

    private fun promoteThenMaybeStop(
        snapshot: CompileProgressBridge.CompileState,
        forceStop: Boolean,
        startId: Int
    ): Int {
        val promoted = promoteForeground(snapshot)
        if (!promoted) {
            Log.e(TAG, "startForeground failed — stopping service (startId=$startId)")
            isForeground = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Re-read live count AFTER promotion. A fast COMPLETED can land between the snapshot
        // used to build the notification and this point; using the stale snapshot would leak FGS.
        if (forceStop || CompilationMonitorLogic.shouldStopAfterPromotion(
                CompileProgressBridge.state.value.activeDomainCount
            )
        ) {
            Log.i(TAG, "No live compile jobs after promotion — stopping")
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(state: CompileProgressBridge.CompileState): Boolean {
        val notification = buildAnchorNotification(state)
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_FGS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            isForeground = true
            Log.i(TAG, "startForeground NOTIF_FGS isActive=${state.isActive} ppu=${state.ppuActive} shader=${state.shaderActive}")

            if (state.ppuActive) {
                NotificationManagerCompat.from(this).notify(NOTIF_PPU, buildPpuSecondary(state))
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_PPU)
            }
            if (state.shaderActive) {
                NotificationManagerCompat.from(this).notify(NOTIF_SHADER, buildShaderSecondary(state))
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_SHADER)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            isForeground = false
            false
        }
    }

    private fun onStateChanged(state: CompileProgressBridge.CompileState) {
        lastState = state
        if (!isForeground) {
            // If we are not yet foreground but state became active, we should have been started via intent.
            // This path handles updates while foreground.
            return
        }

        if (!state.isActive) {
            // Both domains done — stop foreground immediately
            Log.i(TAG, "State inactive — stopping foreground")
            NotificationManagerCompat.from(this).cancel(NOTIF_PPU)
            NotificationManagerCompat.from(this).cancel(NOTIF_SHADER)
            stopForegroundAndSelf()
            return
        }

        // Update anchor and secondaries
        val anchor = buildAnchorNotification(state)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_FGS, anchor)
            if (state.ppuActive) {
                NotificationManagerCompat.from(this).notify(NOTIF_PPU, buildPpuSecondary(state))
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_PPU)
            }
            if (state.shaderActive) {
                NotificationManagerCompat.from(this).notify(NOTIF_SHADER, buildShaderSecondary(state))
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_SHADER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "notify update failed: ${e.message}", e)
        }
    }

    private fun buildAnchorNotification(state: CompileProgressBridge.CompileState): android.app.Notification {
        val title = when {
            state.ppuActive && state.shaderActive -> getString(R.string.compiling_ppu_title) + " + " + getString(R.string.compiling_shaders_title)
            state.ppuActive -> getString(R.string.compiling_ppu_title)
            state.shaderActive -> getString(R.string.compiling_shaders_title)
            else -> getString(R.string.compiling_ppu_title)
        }
        val builder = NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)

        // Content
        if (state.ppuActive && state.shaderActive) {
            // Merged InboxStyle on anchor
            val inbox = NotificationCompat.InboxStyle()
            val ppuLine = state.ppuMsg ?: "PPU ${state.fileDone}/${state.fileTotal} module ${state.moduleDone}/${state.moduleTotal}"
            inbox.addLine(ppuLine)
            inbox.addLine(state.shaderMsg ?: getString(R.string.compiling_shaders_desc))
            builder.setStyle(inbox)
            // Show PPU progress as determinate on anchor
            builder.setProgress(state.ppuMax, state.ppuPercent, false)
            builder.setContentText(ppuLine)
        } else if (state.ppuActive) {
            val msg = state.ppuMsg ?: "Compiling…"
            builder.setContentText(msg)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            if (state.ppuMax > 0) builder.setProgress(state.ppuMax, state.ppuPercent, false)
            else builder.setProgress(0, 0, true)
        } else if (state.shaderActive) {
            val msg = state.shaderMsg ?: getString(R.string.compiling_shaders_desc)
            builder.setContentText(msg)
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun buildPpuSecondary(state: CompileProgressBridge.CompileState): android.app.Notification {
        val msg = state.ppuMsg ?: "PPU ${state.fileDone}/${state.fileTotal}"
        return NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
            .setContentTitle(getString(R.string.compiling_ppu_title))
            .setContentText(msg)
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(state.ppuMax, state.ppuPercent, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()
    }

    private fun buildShaderSecondary(state: CompileProgressBridge.CompileState): android.app.Notification {
        val msg = state.shaderMsg ?: getString(R.string.compiling_shaders_desc)
        return NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
            .setContentTitle(getString(R.string.compiling_shaders_title))
            .setContentText(msg)
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun stopForegroundAndSelf() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).cancel(NOTIF_FGS)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }

    // Defensive: specialUse is not expected to receive the Android 15 dataSync 6h quota
    // callback, but stop promptly if the platform invokes it anyway.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout startId=$startId fgsType=$fgsType — stopping")
        stopForegroundAndSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

internal object CompilationMonitorLogic {
    /** After startForeground, remain up only while live runtime domains are still active. */
    fun shouldStopAfterPromotion(liveActiveDomainCount: Int): Boolean = liveActiveDomainCount == 0
}
