package com.zenithblue.sambas3.session

import com.zenithblue.sambas3.EmulatorState

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
    ): StopResult = EmulatorStopCoordinator.ensureNativeStopped(
        reason = EmulatorStopReason.AppRecoveryCleanup,
        readState = state,
        kill = kill,
        timeoutMs = timeoutMs,
        pollMs = pollMs,
        onLog = { message -> onLog("$message requested=$reason") },
    )
}
