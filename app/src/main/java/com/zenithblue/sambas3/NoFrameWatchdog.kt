package com.zenithblue.sambas3

/** Pure state machine for detecting a running emulator that stopped presenting frames. */
object NoFrameWatchdog {
    data class State(
        val presentedFrameCount: Long? = null,
        val lastFrameProgressAtMs: Long? = null,
    )

    data class Observation(
        val state: State,
        val timedOut: Boolean,
        val stalledForMs: Long = 0L,
    )

    fun observe(
        state: State,
        nowMs: Long,
        shouldWatch: Boolean,
        presentedFrameCount: Long?,
        timeoutMs: Long,
    ): Observation {
        if (!shouldWatch || presentedFrameCount == null || presentedFrameCount < 0L) {
            return Observation(State(), timedOut = false)
        }

        val previousCount = state.presentedFrameCount
        val counterAdvanced = previousCount == null || presentedFrameCount != previousCount
        if (counterAdvanced || state.lastFrameProgressAtMs == null) {
            return Observation(
                State(presentedFrameCount, nowMs),
                timedOut = false,
            )
        }

        val stalledForMs = (nowMs - state.lastFrameProgressAtMs).coerceAtLeast(0L)
        return Observation(
            state,
            timedOut = stalledForMs >= timeoutMs.coerceAtLeast(1L),
            stalledForMs = stalledForMs,
        )
    }
}
