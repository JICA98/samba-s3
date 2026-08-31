package com.zenithblue.sambas3.crash

import com.zenithblue.sambas3.session.EmulationSessionRecord

enum class RecoveryAction {
    Retry,
    ContinueSave,
    PlayFresh,
    ChooseSave,
    SafeRetry,
    ExportReport,
    ViewDetails,
    Dismiss,
}

sealed interface HomeRecoveryState {
    data object None : HomeRecoveryState

    data class Interrupted(
        val session: EmulationSessionRecord,
        val message: String = "Last session did not close cleanly.",
        val report: CrashReport? = null,
    ) : HomeRecoveryState

    data class ConfirmedCrash(
        val session: EmulationSessionRecord,
        val report: CrashReport,
    ) : HomeRecoveryState

    data class LoadFailure(
        val gamePath: String,
        val savestatePath: String?,
        val slot: Int?,
        val reason: String,
        val report: CrashReport? = null,
    ) : HomeRecoveryState

    data class ActionRunning(val action: RecoveryAction) : HomeRecoveryState

    data class ActionFailed(
        val session: EmulationSessionRecord?,
        val message: String,
    ) : HomeRecoveryState
}
