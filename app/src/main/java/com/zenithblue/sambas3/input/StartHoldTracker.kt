package com.zenithblue.sambas3.input

data class StartHoldProgress(
    val progress: Float,
    val completed: Boolean,
)

/** Monotonic START-hold state machine; UI supplies SystemClock.elapsedRealtime(). */
class StartHoldTracker(private val holdMs: Long = 2_000L) {
    private var downAtMs: Long? = null
    private var completionEmitted = false

    fun update(isDown: Boolean, nowMs: Long): StartHoldProgress {
        if (!isDown) {
            downAtMs = null
            completionEmitted = false
            return StartHoldProgress(0f, false)
        }
        if (downAtMs == null) downAtMs = nowMs
        val elapsed = (nowMs - (downAtMs ?: nowMs)).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / holdMs.toFloat()).coerceIn(0f, 1f)
        val completed = progress >= 1f && !completionEmitted
        if (completed) completionEmitted = true
        return StartHoldProgress(progress, completed)
    }
}
