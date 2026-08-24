package com.zenithblue.sambas3

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CompileProgressBridge {
    private const val TAG = "CompileBridge"

    data class CompileState(
        val ppuPercent: Int = 0,
        val ppuMax: Int = 100,
        val ppuMsg: String? = null,
        val ppuActive: Boolean = false,
        val shaderActive: Boolean = false,
        val shaderMsg: String? = null,
        val fileDone: Int = 0,
        val fileTotal: Int = 0,
        val moduleDone: Int = 0,
        val moduleTotal: Int = 0
    ) {
        val activeDomainCount: Int get() = (if (ppuActive) 1 else 0) + (if (shaderActive) 1 else 0)
        val isActive: Boolean get() = ppuActive || shaderActive
    }

    // Internal tracking
    private var registered = false
    private val mainHandler: Handler? by lazy {
        try {
            val looper = Looper.getMainLooper()
            if (looper != null) Handler(looper) else null
        } catch (_: Exception) { null }
    }
    private val _state = MutableStateFlow(CompileState())
    val state: StateFlow<CompileState> = _state.asStateFlow()

    // Install-origin PPU state — for pre-compile Kotlin UI + FGS 3000
    private val _installState = MutableStateFlow(CompileState())
    val installState: StateFlow<CompileState> = _installState.asStateFlow()
    private var installPpuJobId: Long? = null

    // Keep latest runtime event for service cold start promotion
    @Volatile
    private var latestRuntimeEvent: NativeEvent? = null
    @Volatile
    var fgsStartDenied: Boolean = false
        private set

    private val shaderJobIds = mutableSetOf<Long>()
    private var ppuJobId: Long? = null

    data class NativeEvent(
        val domain: Int,
        val phase: Int,
        val origin: Int,
        val jobId: Long,
        val value: Long,
        val max: Long,
        val message: String?,
        val fileDone: Int,
        val fileTotal: Int,
        val moduleDone: Int,
        val moduleTotal: Int
    )

    @Synchronized
    fun registerOnce(context: Context) {
        if (registered) return
        // Check capability — old cores degrade without crash
        try {
            if (!RPCSX.instance.supportsCompileProgressEvents()) {
                Log.w(TAG, "Old runtime core lacks compile progress events — degrading to HUD only")
                registered = true
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "supportsCompileProgressEvents check failed: ${e.message}")
            registered = true
            return
        }

        val appCtx = context.applicationContext
        val callback = RPCSX.CompileProgressCallback { domain, phase, origin, jobId, value, max, message, fileDone, fileTotal, moduleDone, moduleTotal ->
            val ev = NativeEvent(domain, phase, origin, jobId, value, max, message, fileDone, fileTotal, moduleDone, moduleTotal)
            // Ensure reducer runs on main looper (bridge is main-handler reducer)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onNativeEventInternal(ev, appCtx)
            } else {
                val h = mainHandler
                if (h != null) h.post { onNativeEventInternal(ev, appCtx) } else onNativeEventInternal(ev, appCtx)
            }
        }

        try {
            val ok = RPCSX.instance.setCompileProgressListener(callback)
            if (!ok) Log.w(TAG, "setCompileProgressListener returned false — old core?")
            else Log.i(TAG, "CompileProgressListener registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register compile listener: ${e.message}", e)
        }
        registered = true
    }

    // Called from native thread via mainHandler
    private fun onNativeEventInternal(ev: NativeEvent, appCtx: Context?) {
        // Install-origin PPU — route to installState for Kotlin UI + PrecompilerService FGS (3000),
        // not to runtime monitor (2000). Shader never has INSTALL origin.
        if (ev.origin == RPCSX.COMPILE_ORIGIN_INSTALL) {
            if (ev.domain == RPCSX.COMPILE_DOMAIN_PPU) {
                handleInstallPpu(ev, appCtx)
            } else {
                Log.d(TAG, "Ignoring INSTALL-origin shader event job=${ev.jobId}")
            }
            return
        }

        // Reducer keyed by domain/jobId
        when (ev.domain) {
            RPCSX.COMPILE_DOMAIN_PPU -> handlePpu(ev, appCtx)
            RPCSX.COMPILE_DOMAIN_SHADER -> handleShader(ev, appCtx)
            else -> Log.w(TAG, "Unknown domain ${ev.domain}")
        }
    }

    private fun handleInstallPpu(ev: NativeEvent, appCtx: Context?) {
        val cur = _installState.value
        when (ev.phase) {
            RPCSX.COMPILE_PHASE_BEGIN -> {
                if (installPpuJobId != null && installPpuJobId == ev.jobId) return
                installPpuJobId = ev.jobId
                _installState.value = cur.copy(
                    ppuActive = true,
                    ppuPercent = ev.value.toInt().coerceIn(0, 100),
                    ppuMax = if (ev.max > 0) ev.max.toInt() else 100,
                    ppuMsg = ev.message ?: cur.ppuMsg,
                    fileDone = ev.fileDone,
                    fileTotal = ev.fileTotal,
                    moduleDone = ev.moduleDone,
                    moduleTotal = ev.moduleTotal
                )
            }
            RPCSX.COMPILE_PHASE_PROGRESS -> {
                if (installPpuJobId == null) installPpuJobId = ev.jobId
                if (installPpuJobId != ev.jobId) return
                _installState.value = cur.copy(
                    ppuActive = true,
                    ppuPercent = ev.value.toInt().coerceIn(0, 100),
                    ppuMax = if (ev.max > 0) ev.max.toInt() else 100,
                    ppuMsg = ev.message ?: cur.ppuMsg,
                    fileDone = ev.fileDone,
                    fileTotal = ev.fileTotal,
                    moduleDone = ev.moduleDone,
                    moduleTotal = ev.moduleTotal
                )
            }
            RPCSX.COMPILE_PHASE_COMPLETED, RPCSX.COMPILE_PHASE_FAILED, RPCSX.COMPILE_PHASE_CANCELED -> {
                if (installPpuJobId == null || installPpuJobId != ev.jobId) return
                installPpuJobId = null
                _installState.value = cur.copy(ppuActive = false)
            }
        }
        // Notify PrecompilerService to update its FGS notification title to PPU if active
        if (appCtx != null) {
            try {
                // Update PrecompilerService's ongoing notification (3000) to show PPU title when install PPU is active
                val isActive = _installState.value.ppuActive
                val title = if (isActive) appCtx.getString(R.string.compiling_ppu_title) else appCtx.getString(R.string.package_installation)
                val msg = _installState.value.ppuMsg ?: ev.message
                val pct = _installState.value.ppuPercent
                val max = _installState.value.ppuMax
                // Use ProgressRepository helper to update the fixed 3000 notification
                // We don't have Service instance here, so use ordinary notify for now; PrecompilerService observer will promote via its own collector
                // For immediate feedback, post via NotificationManagerCompat
                androidx.core.app.NotificationManagerCompat.from(appCtx).let { nm ->
                    val builder = androidx.core.app.NotificationCompat.Builder(appCtx, NotificationChannels.RPCSX_PROGRESS)
                        .setContentTitle(title)
                        .setContentText(msg ?: title)
                        .setSmallIcon(R.mipmap.ic_sambas3_foreground)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_PROGRESS)
                        .setOngoing(true)
                        .setSilent(true)
                    if (isActive && max > 0) builder.setProgress(max, pct, false) else builder.setProgress(0, 0, true)
                    if (isActive) builder.setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(msg))
                    try { nm.notify(PrecompilerService.NOTIF_INSTALL, builder.build()) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun handlePpu(ev: NativeEvent, appCtx: Context?) {
        val cur = _state.value
        when (ev.phase) {
            RPCSX.COMPILE_PHASE_BEGIN -> {
                if (ppuJobId != null && ppuJobId == ev.jobId) {
                    Log.d(TAG, "Duplicate PPU BEGIN job=${ev.jobId} ignored")
                    return
                }
                ppuJobId = ev.jobId
                latestRuntimeEvent = ev
                _state.value = cur.copy(
                    ppuActive = true,
                    ppuPercent = ev.value.toInt().coerceIn(0, 100),
                    ppuMax = if (ev.max > 0) ev.max.toInt() else 100,
                    ppuMsg = ev.message ?: cur.ppuMsg,
                    fileDone = ev.fileDone,
                    fileTotal = ev.fileTotal,
                    moduleDone = ev.moduleDone,
                    moduleTotal = ev.moduleTotal
                )
                requestMonitorStart(appCtx, ev)
            }
            RPCSX.COMPILE_PHASE_PROGRESS -> {
                // If no active PPU but we get progress, treat as active (missed BEGIN)
                if (ppuJobId == null) ppuJobId = ev.jobId
                if (ppuJobId != ev.jobId && ppuJobId != null) {
                    // Different jobId progress without BEGIN — ignore or treat as new?
                    Log.w(TAG, "PPU PROGRESS job mismatch ${ev.jobId} vs active $ppuJobId")
                    return
                }
                latestRuntimeEvent = ev
                _state.value = cur.copy(
                    ppuActive = true,
                    ppuPercent = ev.value.toInt().coerceIn(0, 100),
                    ppuMax = if (ev.max > 0) ev.max.toInt() else 100,
                    ppuMsg = ev.message ?: cur.ppuMsg,
                    fileDone = ev.fileDone,
                    fileTotal = ev.fileTotal,
                    moduleDone = ev.moduleDone,
                    moduleTotal = ev.moduleTotal
                )
                // If monitor already running, service will observe StateFlow; no need to re-start
                // But ensure FGS started if not yet
                if (!cur.ppuActive) requestMonitorStart(appCtx, ev)
            }
            RPCSX.COMPILE_PHASE_COMPLETED, RPCSX.COMPILE_PHASE_FAILED, RPCSX.COMPILE_PHASE_CANCELED -> {
                if (ppuJobId == null || ppuJobId != ev.jobId) {
                    Log.d(TAG, "PPU terminal for unknown job ${ev.jobId} vs $ppuJobId ignored")
                    return
                }
                ppuJobId = null
                latestRuntimeEvent = null
                _state.value = cur.copy(
                    ppuActive = false,
                    // keep last percent/msg for fade? but clear active flag
                )
                // Reducer will cause service to stop when shader also inactive
            }
        }
    }

    private fun handleShader(ev: NativeEvent, appCtx: Context?) {
        val cur = _state.value
        when (ev.phase) {
            RPCSX.COMPILE_PHASE_BEGIN -> {
                val added = shaderJobIds.add(ev.jobId)
                if (!added) {
                    Log.d(TAG, "Duplicate shader BEGIN job=${ev.jobId} ignored")
                    return
                }
                latestRuntimeEvent = ev
                _state.value = cur.copy(
                    shaderActive = true,
                    shaderMsg = ev.message ?: "Compiling shaders…"
                )
                requestMonitorStart(appCtx, ev)
            }
            RPCSX.COMPILE_PHASE_PROGRESS -> {
                // For now shader has no progress ETA — ignore, keep indeterminate
            }
            RPCSX.COMPILE_PHASE_COMPLETED, RPCSX.COMPILE_PHASE_FAILED, RPCSX.COMPILE_PHASE_CANCELED -> {
                // jobId 0 means cancel all (from ProgramStateCache.clear)
                if (ev.jobId == 0L) {
                    if (shaderJobIds.isNotEmpty()) {
                        shaderJobIds.clear()
                        _state.value = cur.copy(shaderActive = false, shaderMsg = null)
                    }
                    return
                }
                val removed = shaderJobIds.remove(ev.jobId)
                if (!removed) {
                    Log.d(TAG, "Shader terminal for unknown job ${ev.jobId} ignored")
                    return
                }
                _state.value = cur.copy(
                    shaderActive = shaderJobIds.isNotEmpty(),
                    shaderMsg = if (shaderJobIds.isNotEmpty()) cur.shaderMsg ?: "Compiling shaders…" else null
                )
                if (shaderJobIds.isEmpty()) {
                    latestRuntimeEvent = null
                }
            }
        }
    }

    private fun requestMonitorStart(appCtx: Context?, ev: NativeEvent) {
        if (appCtx == null) return // unit test — no FGS start
        // Install-origin already filtered; only runtime reaches here
        try {
            val intent = Intent(appCtx, CompilationMonitorService::class.java).apply {
                putExtra("domain", ev.domain)
                putExtra("phase", ev.phase)
                putExtra("origin", ev.origin)
                putExtra("jobId", ev.jobId)
                putExtra("value", ev.value)
                putExtra("max", ev.max)
                putExtra("message", ev.message)
                putExtra("fileDone", ev.fileDone)
                putExtra("fileTotal", ev.fileTotal)
                putExtra("moduleDone", ev.moduleDone)
                putExtra("moduleTotal", ev.moduleTotal)
            }
            ContextCompat.startForegroundService(appCtx, intent)
            fgsStartDenied = false
            Log.i(TAG, "startForegroundService requested for domain=${ev.domain} phase=${ev.phase} job=${ev.jobId}")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "FGS start denied (background): ${e.message}")
            fgsStartDenied = true
            // Keep StateFlow/UI coherent; do not crash or retry loop
        } catch (e: IllegalStateException) {
            // Some OEMs throw IllegalStateException for background start
            if (e.message?.contains("NotAllowed") == true || e is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "FGS start not allowed: ${e.message}")
                fgsStartDenied = true
            } else {
                Log.e(TAG, "startForegroundService failed: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService failed: ${e.message}", e)
        }
    }

    fun getLatestRuntimeEvent(): NativeEvent? = latestRuntimeEvent

    fun clearForTest() {
        synchronized(this) {
            registered = false
            shaderJobIds.clear()
            ppuJobId = null
            installPpuJobId = null
            latestRuntimeEvent = null
            fgsStartDenied = false
            _state.value = CompileState()
            _installState.value = CompileState()
        }
    }

    // Test helper — bypasses Context/FGS start
    fun injectForTest(ev: NativeEvent) {
        onNativeEventInternal(ev, null)
    }
}
