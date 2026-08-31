package com.zenithblue.sambas3

import com.zenithblue.sambas3.crash.CrashClassification
import com.zenithblue.sambas3.crash.HomeRecoveryDecision
import com.zenithblue.sambas3.crash.RecoveryDecision
import com.zenithblue.sambas3.session.EmulationSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecoveryDecisionTest {
    @Test
    fun expectedStoppingDoesNotCreateARecoveryCardInTheSameProcess() {
        assertEquals(RecoveryDecision.NONE, HomeRecoveryDecision.decide(EmulationSessionState.STOPPING, "InGameExit", true, null))
    }

    @Test
    fun cleanStopWinsOverOldFatalEvidence() {
        assertEquals(RecoveryDecision.NONE, HomeRecoveryDecision.decide(EmulationSessionState.STOPPING, "HomeStop", true, CrashClassification.CLEAN_STOP))
    }

    @Test
    fun currentFatalEvidenceIsConfirmedEvenWhenCleanupAlreadyStopped() {
        assertEquals(RecoveryDecision.CONFIRMED_CRASH, HomeRecoveryDecision.decide(EmulationSessionState.FAILED, "CrashExit", false, CrashClassification.CONFIRMED_CRASH))
    }

    @Test
    fun failedStateWithoutCurrentFatalEvidenceIsInterrupted() {
        assertEquals(RecoveryDecision.INTERRUPTED, HomeRecoveryDecision.decide(EmulationSessionState.FAILED, "BootFailureCleanup", false, CrashClassification.UNEXPECTED_TERMINATION))
    }
}
