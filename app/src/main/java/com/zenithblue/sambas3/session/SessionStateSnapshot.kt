package com.zenithblue.sambas3.session

import com.zenithblue.sambas3.EmulatorState

/** Compact, serializable view used for lifecycle diagnostics and tests. */
data class SessionStateSnapshot(
    val sessionId: String,
    val activityInstanceId: Long,
    val activityState: String,
    val isFinishing: Boolean,
    val isChangingConfigurations: Boolean,
    val nativeState: EmulatorState,
    val mirroredState: EmulatorState,
    val activeGame: String?,
    val surfaceGeneration: Long,
    val recoveryTransitionActive: Boolean,
    val frontendMenuOpen: Boolean,
    val menuState: String,
    val inputNeutral: Boolean,
    val bootThreadAlive: Boolean,
    val reason: String
) {
    fun compact(): String = buildString {
        append("session=$sessionId activity=$activityInstanceId lifecycle=$activityState ")
        append("finishing=$isFinishing changingConfig=$isChangingConfigurations ")
        append("native=$nativeState mirrored=$mirroredState activeGame=${activeGame ?: "null"} ")
        append("surfaceGen=$surfaceGeneration recovery=$recoveryTransitionActive ")
        append("frontendMenu=$frontendMenuOpen menu=$menuState neutral=$inputNeutral ")
        append("bootThread=$bootThreadAlive reason=$reason")
    }
}

data class SessionStatePairing(
    val nativeState: EmulatorState,
    val activeGame: String?
)

object SessionStateReconciliation {
    fun invalidPairing(pairing: SessionStatePairing): String? = when {
        pairing.activeGame != null && pairing.nativeState == EmulatorState.Stopped ->
            "activeGame-present-while-stopped"
        pairing.activeGame == null &&
            (pairing.nativeState == EmulatorState.Running || pairing.nativeState == EmulatorState.Paused) ->
            "activeGame-missing-while-alive"
        else -> null
    }
}
