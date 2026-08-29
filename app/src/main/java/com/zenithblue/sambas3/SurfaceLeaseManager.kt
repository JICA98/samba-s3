package com.zenithblue.sambas3

import android.util.Log
import android.view.Surface
import android.widget.FrameLayout

/**
 * Owns the short-lived Android SurfaceView generations used by RPCSX.
 * A replacement is created only after the old SurfaceHolder has delivered
 * surfaceDestroyed and native surface release has returned.
 */
class SurfaceLeaseManager(private val host: FrameLayout) : GraphicsFrame.Listener {
    private var nextGeneration = 0L
    private var current: GraphicsFrame? = null
    private var pendingReady: (() -> Unit)? = null
    private var destroying = false

    val currentGeneration: Long get() = current?.generation ?: 0L

    fun installInitial() {
        destroying = false
        if (current == null) createGeneration()
    }

    fun replace(onReady: () -> Unit) {
        check(!destroying) { "surface host is being destroyed" }
        check(pendingReady == null) { "surface replacement already pending" }
        pendingReady = onReady
        val old = current
        if (old == null) {
            pendingReady = null
            readyCallbacks += onReady
            createGeneration()
        } else if (!old.hasCreatedSurface) {
            // No native surface was ever attached, so there is no destroy
            // callback to await.
            current = null
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
        if (!old.hasCreatedSurface) current = null
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
        val accepted = RPCSX.instance.surfaceEventV2(surface, 0, generation)
        Log.i(TAG, "S3SURFACE created generation=$generation accepted=$accepted")
        if (destroying) {
            RPCSX.instance.surfaceEventV2(surface, 2, generation)
            current = null
            Log.i(TAG, "S3SURFACE destroyed-before-ready generation=$generation")
            return
        }
        readyCallbacks.removeFirstOrNull()?.invoke()
    }

    override fun onSurfaceChanged(frame: GraphicsFrame, generation: Long, surface: Surface) {
        if (frame !== current || generation != currentGeneration) {
            Log.w(TAG, "S3SURFACE stale-changed generation=$generation current=$currentGeneration")
            return
        }
        RPCSX.instance.surfaceEventV2(surface, 1, generation)
        Log.i(TAG, "S3SURFACE changed generation=$generation")
    }

    override fun onSurfaceDestroyed(frame: GraphicsFrame, generation: Long, surface: Surface) {
        if (frame !== current || generation != currentGeneration) {
            Log.w(TAG, "S3SURFACE stale-destroyed generation=$generation current=$currentGeneration")
            return
        }
        RPCSX.instance.surfaceEventV2(surface, 2, generation)
        Log.i(TAG, "S3SURFACE destroyed generation=$generation native-release-return")
        current = null
        if (destroying) return
        val ready = pendingReady
        pendingReady = null
        if (ready != null) {
            readyCallbacks += ready
            createGeneration()
        }
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
