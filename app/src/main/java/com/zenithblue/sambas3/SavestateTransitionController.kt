package com.zenithblue.sambas3

/**
 * UI-side state machine for the normal in-game save transition.
 *
 * The native save completion event is only an acknowledgement that the slot
 * is durable.  It is not permission to boot until the old surface has been
 * released and a fresh surface generation is ready.  Keeping those phases
 * explicit prevents duplicate callbacks and makes recovery independent from
 * menu navigation.
 */
class SavestateTransitionController {
    enum class Phase {
        Idle,
        Saving,
        SavedAwaitingSurfaceReset,
        CreatingFreshSurface,
        BootingSavedState,
        AwaitingFirstFrame,
        Completed,
        Failed
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val requestId: Long = 0L,
        val slot: Int = -1,
        val savestatePath: String = "",
        val failure: String = ""
    )

    @Volatile
    var state: State = State()
        private set

    @Synchronized
    fun begin(requestId: Long, slot: Int): Boolean {
        if (state.phase != Phase.Idle && state.phase != Phase.Completed && state.phase != Phase.Failed) {
            return false
        }
        state = State(Phase.Saving, requestId, slot)
        return true
    }

    /** Enters the first-frame phase for a recovery boot after process death. */
    @Synchronized
    fun beginRecoveryBoot(requestId: Long, slot: Int, path: String): Boolean {
        if (state.phase != Phase.Idle && state.phase != Phase.Completed && state.phase != Phase.Failed) {
            return false
        }
        state = State(Phase.AwaitingFirstFrame, requestId, slot, path)
        return true
    }

    @Synchronized
    fun committed(requestId: Long, slot: Int, path: String): Boolean {
        if (!matches(requestId, slot) || state.phase != Phase.Saving || path.isBlank()) return false
        state = state.copy(phase = Phase.SavedAwaitingSurfaceReset, savestatePath = path)
        return true
    }

    @Synchronized
    fun surfaceResetStarted(): Boolean {
        if (state.phase != Phase.SavedAwaitingSurfaceReset) return false
        state = state.copy(phase = Phase.CreatingFreshSurface)
        return true
    }

    @Synchronized
    fun surfaceReady(requestId: Long, slot: Int): Boolean {
        if (!matches(requestId, slot) || state.phase != Phase.CreatingFreshSurface) return false
        state = state.copy(phase = Phase.BootingSavedState)
        return true
    }

    @Synchronized
    fun bootStarted(requestId: Long, slot: Int): Boolean {
        if (!matches(requestId, slot) || state.phase != Phase.BootingSavedState) return false
        state = state.copy(phase = Phase.AwaitingFirstFrame)
        return true
    }

    @Synchronized
    fun firstFrameConfirmed(requestId: Long, slot: Int): Boolean {
        if (!matches(requestId, slot) || state.phase != Phase.AwaitingFirstFrame) return false
        state = state.copy(phase = Phase.Completed)
        return true
    }

    @Synchronized
    fun fail(reason: String): Boolean {
        if (state.phase == Phase.Idle || state.phase == Phase.Completed || state.phase == Phase.Failed) return false
        state = state.copy(phase = Phase.Failed, failure = reason)
        return true
    }

    @Synchronized
    fun reset() {
        state = State()
    }

    private fun matches(requestId: Long, slot: Int): Boolean =
        state.requestId == requestId && state.slot == slot
}
