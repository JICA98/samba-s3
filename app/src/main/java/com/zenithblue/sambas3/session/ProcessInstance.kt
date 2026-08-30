package com.zenithblue.sambas3.session

import android.os.Process
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Identity shared by all Activities in one Android process. */
object ProcessInstance {
    val id: String = UUID.randomUUID().toString()
    val startedElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()
    val pid: Int = Process.myPid()
    private val nextActivity = AtomicLong(0)

    fun nextActivityId(): Long = nextActivity.incrementAndGet()
}
