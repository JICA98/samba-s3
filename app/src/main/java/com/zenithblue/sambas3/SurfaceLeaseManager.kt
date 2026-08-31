package com.zenithblue.sambas3

import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Owns the short-lived Android SurfaceView generations used by RPCSX.
 * A replacement is created only after the old SurfaceHolder has delivered
 * surfaceDestroyed and native surface release has returned.
 */
fun interface SurfaceLeaseBridge {
    fun event(surface: Surface, event: Int, generation: Long): Boolean
}

enum class SurfaceReadyResult {
    Ready,
    TimedOut,
    Failed,
    Destroying
}

class SurfaceLeaseManager(
    private val host: FrameLayout,
    private val bridge: SurfaceLeaseBridge = SurfaceLeaseBridge { surface, event, generation ->
        RPCSX.instance.surfaceEventV2(surface, event, generation)
    }
) : GraphicsFrame.Listener {
    var onFailure: ((String) -> Unit)? = null
    private var nextGeneration = 0L
    private var current: GraphicsFrame? = null
    private var currentSurfaceCreated = false
    private var pendingReady: (() -> Unit)? = null
    private var destroying = false
    private val readyWaiters = mutableListOf<(SurfaceReadyResult) -> Unit>()

    val currentGeneration: Long get() = current?.generation ?: 0L
    val currentFrame: GraphicsFrame? get() = current

    fun installInitial() {
        destroying = false
        if (current == null) createGeneration()
    }

    /**
     * Waits for the current generation to have both an Android surface and an
     * accepted native CREATE event.  A non-null GraphicsFrame alone is not
     * sufficient: SurfaceView creation is asynchronous.
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000L): SurfaceReadyResult {
        val result = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            suspendCancellableCoroutine { continuation ->
                val immediate = synchronized(this@SurfaceLeaseManager) {
                    when {
                        destroying -> SurfaceReadyResult.Destroying
                        current != null && currentSurfaceCreated -> SurfaceReadyResult.Ready
                        else -> {
                            val waiter: (SurfaceReadyResult) -> Unit = { result ->
                                if (continuation.isActive) continuation.resume(result)
                            }
                            readyWaiters += waiter
                            continuation.invokeOnCancellation { synchronized(this@SurfaceLeaseManager) { readyWaiters.remove(waiter) } }
                            null
                        }
                    }
                }
                if (immediate != null && continuation.isActive) continuation.resume(immediate)
            }
        }
        return result ?: SurfaceReadyResult.TimedOut
    }

    /** Named API for boot code; readiness includes the generation accepted by native CREATE. */
    suspend fun awaitInitialReady(timeoutMs: Long = 10_000L): SurfaceReadyResult = awaitReady(timeoutMs)

    fun replace(onReady: () -> Unit) {
        check(!destroying) { "surface host is being destroyed" }
        check(pendingReady == null) { "surface replacement already pending" }
        pendingReady = onReady
        val old = current
        if (old == null) {
            pendingReady = null
            readyCallbacks += onReady
            createGeneration()
        } else if (!currentSurfaceCreated) {
            // No native surface was ever attached, so there is no destroy
            // callback to await.
            current = null
            currentSurfaceCreated = false
            pendingReady = null
            readyCallbacks += onReady
            createGeneration()
        } else {
            Log.i(TAG, "S3SURFACE destroy-begin generation=${old.generation}")
            host.removeView(old)
        }
    }

    fun destroy() {
        destroying = true
        pendingReady = null
        val old = current ?: return
        host.removeView(old)
        // Keep current until surfaceDestroyed arrives so that callback still
        // releases the native window. Clearing it here used to turn the
        // terminal callback into a stale event and leak the old ANativeWindow.
        if (!currentSurfaceCreated) current = null
    }

    private fun createGeneration() {
        val frame = GraphicsFrame(host.context).also {
            it.generation = ++nextGeneration
            it.listener = this
        }
        current = frame
        host.addView(frame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        Log.i(TAG, "S3SURFACE new-generation-created generation=${frame.generation}")
    }

    override fun onSurfaceCreated(frame: GraphicsFrame, generation: Long, surface: Surface) {
        if (frame !== current || generation != currentGeneration) {
            Log.w(TAG, "S3SURFACE stale-created generation=$generation current=$currentGeneration")
            return
        }
        val accepted = bridge.event(surface, 0, generation)
        Log.i(TAG, "S3SURFACE created generation=$generation accepted=$accepted")
        if (!accepted) {
            completeReady(SurfaceReadyResult.Failed)
            onFailure?.invoke("native-create-failed-generation-$generation")
            return
        }
        currentSurfaceCreated = true
        if (destroying) {
            bridge.event(surface, 2, generation)
            current = null
            Log.i(TAG, "S3SURFACE destroyed-before-ready generation=$generation")
            return
        }
        completeReady(SurfaceReadyResult.Ready)
        readyCallbacks.removeFirstOrNull()?.invoke()
    }

    override fun onSurfaceChanged(frame: GraphicsFrame, generation: Long, surface: Surface) {
        if (frame !== current || generation != currentGeneration) {
            Log.w(TAG, "S3SURFACE stale-changed generation=$generation current=$currentGeneration")
            return
        }
        val accepted = bridge.event(surface, 1, generation)
        Log.i(TAG, "S3SURFACE changed generation=$generation accepted=$accepted")
        if (!accepted) onFailure?.invoke("native-change-failed-generation-$generation")
    }

    override fun onSurfaceDestroyed(frame: GraphicsFrame, generation: Long, surface: Surface) {
        if (frame !== current || generation != currentGeneration) {
            Log.w(TAG, "S3SURFACE stale-destroyed generation=$generation current=$currentGeneration")
            return
        }
        val released = bridge.event(surface, 2, generation)
        Log.i(TAG, "S3SURFACE destroyed generation=$generation native-release-return=$released")
        if (!released) {
            completeReady(SurfaceReadyResult.Failed)
            onFailure?.invoke("native-destroy-failed-generation-$generation")
            return
        }
        currentSurfaceCreated = false
        current = null
        if (destroying) return
        val ready = pendingReady
        pendingReady = null
        if (ready != null) {
            readyCallbacks += ready
            createGeneration()
        }
    }

    private fun completeReady(result: SurfaceReadyResult) {
        val waiters = synchronized(this) {
            if (readyWaiters.isEmpty()) return
            readyWaiters.toList().also { readyWaiters.clear() }
        }
        waiters.forEach { it(result) }
    }

    private val readyCallbacks = ArrayDeque<() -> Unit>()

    init {
        // A frame may be created before its SurfaceHolder callback arrives.
        // The callback is drained in onSurfaceCreated below.
    }

    companion object {
        private const val TAG = "S3SURFACE"
    }
}
