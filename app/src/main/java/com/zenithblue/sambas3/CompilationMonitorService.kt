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
import kotlinx.coroutines.flow.combine
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
    private var lastProjection: CompilationMonitorLogic.MonitorProjection? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureCreated(this)
        // Observe runtime + prelaunch together so PRELAUNCH Runtime PPU owns FGS 2000
        // without collapsing origin/job ownership into a single boolean.
        collectJob = combine(
            CompileProgressBridge.state,
            CompileProgressBridge.prelaunchState,
        ) { runtime, prelaunch -> CompilationMonitorLogic.project(runtime, prelaunch) }
            .onEach { projection -> onProjectionChanged(projection) }
            .launchIn(serviceScope)
        Log.i(TAG, "onCreate collected runtime+prelaunch projection")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val domain = intent?.getIntExtra("domain", -1) ?: -1
        val phase = intent?.getIntExtra("phase", -1) ?: -1
        val origin = intent?.getIntExtra("origin", -1) ?: -1
        val jobId = intent?.getLongExtra("jobId", -1L) ?: -1L

        Log.i(TAG, "onStartCommand domain=$domain phase=$phase origin=$origin job=$jobId startId=$startId")

        // Install-origin PPU is owned by PrecompilerService. We were still started via
        // startForegroundService, so we must promote then stop to satisfy the ~5s contract.
        if (CompilationMonitorLogic.shouldIgnoreInstallOrigin(origin)) {
            Log.w(TAG, "Ignoring INSTALL-origin start request domain=$domain job=$jobId")
            val projection = currentProjection()
            return promoteThenMaybeStop(projection, forceStop = true, startId = startId)
        }

        val projection = currentProjection()
        val intentJobActive = domain != -1 && jobId != -1L &&
            CompileProgressBridge.isMonitorJobActive(domain, jobId, origin)
        // Never reconstruct an active domain from a stale BEGIN extra. Live StateFlow is source of truth.
        val forceStop = CompilationMonitorLogic.shouldStopAfterPromotion(
            projection.runtime.activeDomainCount,
            projection.prelaunchActive,
        ) && !intentJobActive
        return promoteThenMaybeStop(projection, forceStop = forceStop, startId = startId)
    }

    private fun currentProjection(): CompilationMonitorLogic.MonitorProjection =
        CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )

    private fun promoteThenMaybeStop(
        projection: CompilationMonitorLogic.MonitorProjection,
        forceStop: Boolean,
        startId: Int
    ): Int {
        val promoted = promoteForeground(projection)
        if (!promoted) {
            Log.e(TAG, "startForeground failed — stopping service (startId=$startId)")
            isForeground = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Re-read live projection AFTER promotion. A fast COMPLETED can land between the snapshot
        // used to build the notification and this point; using the stale snapshot would leak FGS.
        val live = currentProjection()
        if (forceStop || CompilationMonitorLogic.shouldStopAfterPromotion(
                live.runtime.activeDomainCount,
                live.prelaunchActive,
            )
        ) {
            Log.i(TAG, "No live compile jobs after promotion — stopping")
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    private fun promoteForeground(projection: CompilationMonitorLogic.MonitorProjection): Boolean {
        val notification = buildAnchorNotification(projection)
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_FGS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            isForeground = true
            Log.i(
                TAG,
                "startForeground NOTIF_FGS isActive=${projection.isActive} " +
                    "runtimePpu=${projection.runtime.ppuActive} shader=${projection.runtime.shaderActive} " +
                    "prelaunch=${projection.prelaunchActive}"
            )
            // The foreground notification is the single runtime compile notification.
            // Older builds created one notification per domain, which made one PPU job
            // appear two or three times in the notification shade.
            cancelSecondaryNotifications()
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            isForeground = false
            false
        }
    }

    private fun onProjectionChanged(projection: CompilationMonitorLogic.MonitorProjection) {
        lastProjection = projection
        if (!isForeground) {
            // If we are not yet foreground but state became active, we should have been started via intent.
            // This path handles updates while foreground.
            return
        }

        if (!projection.isActive) {
            Log.i(TAG, "Projection inactive — stopping foreground")
            stopForegroundAndSelf()
            return
        }

        val anchor = buildAnchorNotification(projection)
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_FGS, anchor)
            cancelSecondaryNotifications()
        } catch (e: Exception) {
            Log.e(TAG, "notify update failed: ${e.message}", e)
        }
    }

    private fun buildAnchorNotification(projection: CompilationMonitorLogic.MonitorProjection): android.app.Notification {
        val state = CompilationMonitorLogic.contentState(projection)
        val title = CompilationMonitorLogic.notificationTitle(
            projection,
            compilingPpu = getString(R.string.compiling_ppu_title),
            compilingShaders = getString(R.string.compiling_shaders_title),
            preparingRuntimePpu = "Preparing Runtime PPU",
        )
        val builder = NotificationCompat.Builder(this, NotificationChannels.RPCSX_PROGRESS)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.ic_sambas3_foreground)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)

        if (state.ppuActive && state.shaderActive) {
            val inbox = NotificationCompat.InboxStyle()
            val ppuLine = state.ppuMsg ?: "PPU ${state.fileDone}/${state.fileTotal} module ${state.moduleDone}/${state.moduleTotal}"
            inbox.addLine(ppuLine)
            inbox.addLine(state.shaderMsg ?: getString(R.string.compiling_shaders_desc))
            builder.setStyle(inbox)
            builder.setProgress(state.ppuMax, state.ppuPercent, false)
            builder.setContentText(ppuLine)
        } else if (state.ppuActive) {
            val msg = state.ppuMsg ?: "Compiling PPU modules…"
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

    private fun cancelSecondaryNotifications() {
        NotificationManagerCompat.from(this).apply {
            cancel(NOTIF_PPU)
            cancel(NOTIF_SHADER)
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).apply {
                cancel(NOTIF_FGS)
                cancelSecondaryNotifications()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
        isForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        collectJob?.cancel()
        serviceScope.cancel()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            NotificationManagerCompat.from(this).apply {
                cancel(NOTIF_FGS)
                cancelSecondaryNotifications()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup onDestroy failed: ${e.message}")
        }
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

    /** Combined stop decision across runtime StateFlow + prelaunch StateFlow. */
    fun shouldStopAfterPromotion(
        runtimeActiveDomainCount: Int,
        prelaunchActive: Boolean,
    ): Boolean = runtimeActiveDomainCount == 0 && !prelaunchActive

    enum class MonitorOwner {
        PRELAUNCH_PPU,
        RUNTIME_PPU,
        RUNTIME_SHADER,
    }

    data class MonitorProjection(
        val runtime: CompileProgressBridge.CompileState,
        val prelaunch: CompileProgressBridge.CompileState,
    ) {
        val prelaunchActive: Boolean get() = prelaunch.ppuActive
        val runtimeActive: Boolean get() = runtime.isActive
        val isActive: Boolean get() = runtimeActive || prelaunchActive
        val activeOwners: Set<MonitorOwner>
            get() = buildSet {
                if (prelaunch.ppuActive) add(MonitorOwner.PRELAUNCH_PPU)
                if (runtime.ppuActive) add(MonitorOwner.RUNTIME_PPU)
                if (runtime.shaderActive) add(MonitorOwner.RUNTIME_SHADER)
            }
    }

    fun project(
        runtime: CompileProgressBridge.CompileState,
        prelaunch: CompileProgressBridge.CompileState,
    ): MonitorProjection = MonitorProjection(runtime, prelaunch)

    /** INSTALL origin must never create/keep a runtime-monitor job. */
    fun shouldIgnoreInstallOrigin(origin: Int): Boolean =
        origin == RPCSX.COMPILE_ORIGIN_INSTALL

    fun shouldOwnPrelaunch(origin: Int, domain: Int): Boolean =
        origin == RPCSX.COMPILE_ORIGIN_PRELAUNCH && domain == RPCSX.COMPILE_DOMAIN_PPU

    fun notificationTitle(
        projection: MonitorProjection,
        compilingPpu: String,
        compilingShaders: String,
        preparingRuntimePpu: String,
    ): String {
        val owners = projection.activeOwners
        return when {
            MonitorOwner.PRELAUNCH_PPU in owners &&
                (MonitorOwner.RUNTIME_SHADER in owners || MonitorOwner.RUNTIME_PPU in owners) ->
                preparingRuntimePpu + " + " + compilingShaders
            MonitorOwner.PRELAUNCH_PPU in owners -> preparingRuntimePpu
            MonitorOwner.RUNTIME_PPU in owners && MonitorOwner.RUNTIME_SHADER in owners ->
                compilingPpu + " + " + compilingShaders
            MonitorOwner.RUNTIME_PPU in owners -> compilingPpu
            MonitorOwner.RUNTIME_SHADER in owners -> compilingShaders
            else -> compilingPpu
        }
    }

    fun contentState(projection: MonitorProjection): CompileProgressBridge.CompileState {
        // Prefer prelaunch progress for the anchor when that owner is active;
        // keep runtime shader/PPU details when only those remain.
        return when {
            projection.prelaunchActive && projection.runtime.shaderActive ->
                projection.prelaunch.copy(
                    shaderActive = true,
                    shaderMsg = projection.runtime.shaderMsg,
                )
            projection.prelaunchActive -> projection.prelaunch
            else -> projection.runtime
        }
    }
}
