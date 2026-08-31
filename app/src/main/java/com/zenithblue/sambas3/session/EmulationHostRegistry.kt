package com.zenithblue.sambas3.session

import android.util.Log
import java.lang.ref.WeakReference

/** Android host boundary used by the process-wide stop coordinator. */
interface EmulationHost {
    val activityInstanceId: Long
    val currentSurfaceGeneration: Long

    /** Called on the main thread before native teardown begins. */
    fun prepareForExternalStop(reason: EmulatorStopReason)

    /** Called on the main thread only after native state is proven Stopped. */
    fun finishAfterExternalStop(requestId: Long)
}

/** Holds only a weak Activity reference so shutdown ownership cannot leak a host. */
object EmulationHostRegistry {
    private const val TAG = "S3HOST"
    private val lock = Any()
    private var current: WeakReference<EmulationHost>? = null

    fun register(host: EmulationHost) {
        synchronized(lock) {
            val previous = current?.get()
            if (previous != null && previous !== host) {
                Log.w(TAG, "register replacing activity=${previous.activityInstanceId} with activity=${host.activityInstanceId}")
            }
            current = WeakReference(host)
        }
        Log.i(TAG, "register activity=${host.activityInstanceId} surface=${host.currentSurfaceGeneration}")
    }

    fun unregister(host: EmulationHost) {
        val removed = synchronized(lock) {
            if (current?.get() === host) {
                current = null
                true
            } else {
                false
            }
        }
        if (removed) Log.i(TAG, "unregister activity=${host.activityInstanceId}")
    }

    fun current(): EmulationHost? = synchronized(lock) { current?.get() }

    internal fun clearForTest() {
        synchronized(lock) { current = null }
    }
}
