package com.zenithblue.sambas3.ui.emulation

enum class EmulatorInteractionLock { Unlocked, SavestateSaving, SavestateLoading, CrashView, BootTransition, ExternalStop }

/** Activity-owned lock boundary. All input entry points consult this one state. */
class InteractionLock {
    @Volatile var state: EmulatorInteractionLock = EmulatorInteractionLock.Unlocked
        private set

    fun lock(next: EmulatorInteractionLock): Boolean {
        if (state != EmulatorInteractionLock.Unlocked) return false
        state = next
        return true
    }

    fun forceLock(next: EmulatorInteractionLock) { state = next }

    fun unlock() { state = EmulatorInteractionLock.Unlocked }
    fun isLocked(): Boolean = state != EmulatorInteractionLock.Unlocked
}
