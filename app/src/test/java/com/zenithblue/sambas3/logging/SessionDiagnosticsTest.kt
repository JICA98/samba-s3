package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.crash.CrashClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDiagnosticsTest {
    private fun manifest(
        terminal: LogSessionTerminal,
        reason: String?,
        sessionId: String = "s1",
    ) = LogSessionManifest(
        sessionId = sessionId,
        gamePath = "/games/BLUS31584",
        titleId = "BLUS31584",
        gameTitleSnapshot = "GTA San Andreas",
        gameIconSnapshotPath = null,
        startedAtMs = 1L,
        terminalState = terminal,
        stopReason = reason,
    )

    @Test
    fun cleanStopInGameExitIsNotCrash() {
        val view = SessionDiagnostics.project(manifest(LogSessionTerminal.CLEAN_STOP, "InGameExit"))
        assertEquals(SessionOutcome.CLEAN_STOP, view.outcome)
        assertEquals(CrashClassification.CLEAN_STOP, view.classification)
        assertEquals("InGameExit", view.rawStopReason)
        assertFalse(SessionDiagnostics.isCrashSession(view))
    }

    @Test
    fun homeStopIsClean() {
        val view = SessionDiagnostics.project(manifest(LogSessionTerminal.CLEAN_STOP, "HomeStop"))
        assertEquals(SessionOutcome.CLEAN_STOP, view.outcome)
        assertFalse(SessionDiagnostics.isCrashSession(view))
    }

    @Test
    fun typedFatalThenCleanupDoesNotDowngrade() {
        val view = SessionDiagnostics.project(
            manifest(LogSessionTerminal.CLEAN_STOP, "HomeStop"),
            fatalEventId = "fatal-1",
        )
        assertEquals(SessionOutcome.CONFIRMED_CRASH, view.outcome)
        assertTrue(SessionDiagnostics.isCrashSession(view))
    }

    @Test
    fun bootFailureIsDistinct() {
        val view = SessionDiagnostics.project(manifest(LogSessionTerminal.FAILED, "BootFailureCleanup"))
        assertEquals(SessionOutcome.BOOT_FAILURE, view.outcome)
        assertEquals(DiagnosticCause.BOOT_LOAD_FAILURE, view.diagnosticCause)
    }

    @Test
    fun preferTerminalNeverDowngradesCrash() {
        assertEquals(
            LogSessionTerminal.CRASHED,
            SessionDiagnostics.preferTerminal(LogSessionTerminal.CRASHED, LogSessionTerminal.CLEAN_STOP),
        )
    }

    @Test
    fun unknownLegacyStaysUncertain() {
        val view = SessionDiagnostics.project(manifest(LogSessionTerminal.INTERRUPTED, "mystery"))
        assertEquals(SessionOutcome.INTERRUPTED, view.outcome)
        assertEquals(DiagnosticCause.UNPROVEN_STOP, view.diagnosticCause)
    }
}
