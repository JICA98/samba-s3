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
    // Strong ref so the SAM is not collected before the JNI GlobalRef is installed.
    private var nativeCallback: RPCSX.CompileProgressCallback? = null
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
            nativeCallback = callback
            val ok = RPCSX.instance.setCompileProgressListener(callback)
            if (!ok) {
                nativeCallback = null
                Log.w(TAG, "setCompileProgressListener returned false — JNI onEvent lookup failed or old core; will retry")
                return
            }
            Log.i(TAG, "CompileProgressListener registered")
            registered = true
        } catch (e: Exception) {
            nativeCallback = null
            Log.e(TAG, "Failed to register compile listener: ${e.message}", e)
        }
    }

    // Called from native thread via mainHandler
    private fun onNativeEventInternal(ev: NativeEvent, appCtx: Context?) {
        // Install-origin PPU — route to installState for Kotlin UI + PrecompilerService FGS (3000),
        // not to runtime monitor (2000). Shader never has INSTALL origin.
        if (ev.origin == RPCSX.COMPILE_ORIGIN_INSTALL) {
            if (ev.domain == RPCSX.COMPILE_DOMAIN_PPU) {
                handleInstallPpu(ev)
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

    private fun handleInstallPpu(ev: NativeEvent) {
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
        // PrecompilerService observes installState and updates FGS 3000. Do not notify() here —
        // racing the service's startForeground with a plain notify can hide the FGS notification.
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
                if (shaderJobIds.isEmpty()) latestRuntimeEvent = null
                _state.value = cur.copy(
                    ppuActive = false,
                )
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
                if (shaderJobIds.isEmpty() && ppuJobId == null) {
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

    fun isRuntimeJobActive(domain: Int, jobId: Long): Boolean {
        if (jobId <= 0L) return false
        return when (domain) {
            RPCSX.COMPILE_DOMAIN_PPU -> ppuJobId == jobId
            RPCSX.COMPILE_DOMAIN_SHADER -> shaderJobIds.contains(jobId)
            else -> false
        }
    }

    fun clearForTest() {
        synchronized(this) {
            registered = false
            nativeCallback = null
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
