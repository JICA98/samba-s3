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
        // Synchronous promotion from intent payload or latest bridge state
        val domain = intent?.getIntExtra("domain", -1) ?: -1
        val phase = intent?.getIntExtra("phase", -1) ?: -1
        val origin = intent?.getIntExtra("origin", -1) ?: -1
        val jobId = intent?.getLongExtra("jobId", -1L) ?: -1L

        // Ignore install-origin PPU — owned by PrecompilerService
        if (origin == RPCSX.COMPILE_ORIGIN_INSTALL) {
            Log.w(TAG, "Ignoring INSTALL-origin start request domain=$domain job=$jobId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Validate we have an active event; if intent is null/empty, check bridge latest
        val hasValidEvent = domain != -1 && phase != -1 && jobId != -1L
        val latest = CompileProgressBridge.getLatestRuntimeEvent()
        val latestState = CompileProgressBridge.state.value

        if (!hasValidEvent && (latest == null || !latestState.isActive)) {
            Log.w(TAG, "onStartCommand with no active event — stopping (startId=$startId)")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Build and promote synchronously before returning (5s requirement)
        val stateToRender = if (hasValidEvent) {
            // Use intent snapshot for immediate promotion; collector will reconcile
            // For PPU, build state from intent
            val safeIntent = intent!!
            if (domain == RPCSX.COMPILE_DOMAIN_PPU) {
                CompileProgressBridge.CompileState(
                    ppuActive = true,
                    ppuPercent = safeIntent.getLongExtra("value", 0).toInt(),
                    ppuMax = safeIntent.getLongExtra("max", 100).toInt().let { if (it==0) 100 else it },
                    ppuMsg = safeIntent.getStringExtra("message"),
                    fileDone = safeIntent.getIntExtra("fileDone", 0),
                    fileTotal = safeIntent.getIntExtra("fileTotal", 0),
                    moduleDone = safeIntent.getIntExtra("moduleDone", 0),
                    moduleTotal = safeIntent.getIntExtra("moduleTotal", 0),
                    shaderActive = latestState.shaderActive,
                    shaderMsg = latestState.shaderMsg
                )
            } else {
                CompileProgressBridge.CompileState(
                    shaderActive = true,
                    shaderMsg = safeIntent.getStringExtra("message") ?: "Compiling shaders…",
                    ppuActive = latestState.ppuActive,
                    ppuMsg = latestState.ppuMsg,
                    ppuPercent = latestState.ppuPercent,
                    ppuMax = latestState.ppuMax
                )
            }
        } else {
            latestState
        }

        promoteForeground(stateToRender)

        // If after promotion the state is already inactive (e.g., quick terminal), stop immediately
        if (!stateToRender.isActive && CompileProgressBridge.state.value.activeDomainCount == 0) {
            Log.i(TAG, "No active domains after promotion — stopping")
            stopForegroundAndSelf()
        }

        return START_NOT_STICKY
    }

    private fun promoteForeground(state: CompileProgressBridge.CompileState) {
        val notification = buildAnchorNotification(state)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_FGS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            isForeground = true
            Log.i(TAG, "startForeground NOTIF_FGS isActive=${state.isActive} ppu=${state.ppuActive} shader=${state.shaderActive}")

            // Post secondaries if needed (ordinary ongoing notifications)
            // Plan allows either merged InboxStyle on anchor OR secondaries. We keep anchor single,
            // and also post secondaries for independent cancel semantics but never stopForeground per-domain.
            // Secondaries are ordinary notify() only.
            if (state.ppuActive) {
                val ppuNotif = buildPpuSecondary(state)
                NotificationManagerCompat.from(this).notify(NOTIF_PPU, ppuNotif)
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_PPU)
            }
            if (state.shaderActive) {
                val shaderNotif = buildShaderSecondary(state)
                NotificationManagerCompat.from(this).notify(NOTIF_SHADER, shaderNotif)
            } else {
                NotificationManagerCompat.from(this).cancel(NOTIF_SHADER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
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

    override fun onBind(intent: Intent?): IBinder? = null
}
