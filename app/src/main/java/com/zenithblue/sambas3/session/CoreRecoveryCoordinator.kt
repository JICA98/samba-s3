package com.zenithblue.sambas3.session

import com.zenithblue.sambas3.EmulatorState
import kotlinx.coroutines.delay

enum class StopResult { AlreadyStopped, Stopped, TimedOut, Failed }

/** Single bounded stop gate used before any fresh title boot or retry. */
object CoreRecoveryCoordinator {
    suspend fun ensureStoppedForFreshBoot(
        reason: String,
        state: () -> EmulatorState,
        kill: () -> Unit,
        timeoutMs: Long = 15_000L,
        pollMs: Long = 100L,
        onLog: (String) -> Unit = {}
    ): StopResult {
        val before = runCatching { state() }.getOrElse {
            onLog("reason=$reason state-read-failed=${it.message}")
            return StopResult.Failed
        }
        onLog("reason=$reason preflight state=$before")
        if (before == EmulatorState.Stopped) return StopResult.AlreadyStopped
        if (before != EmulatorState.Stopping) {
            runCatching { kill() }.onFailure {
                onLog("reason=$reason kill-failed=${it.message}")
                return StopResult.Failed
            }
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val current = runCatching { state() }.getOrElse { return StopResult.Failed }
            if (current == EmulatorState.Stopped) {
                onLog("reason=$reason stopped")
                return StopResult.Stopped
            }
            delay(pollMs)
        }
        val finalState = runCatching { state() }.getOrNull()
        onLog("reason=$reason timeout finalState=$finalState")
        return StopResult.TimedOut
    }
}
