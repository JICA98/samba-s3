package com.zenithblue.sambas3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompileWatchdogLogicTest {

    @Test
    fun stuckAt100WithoutTerminal_clearsUiOnly_neverValidatesReady() {
        val decision = CompileWatchdogLogic.evaluateStuckAtComplete(
            ppuActive = true,
            ppuPercent = 100,
            moduleDone = 9,
            moduleTotal = 9,
            jobMatches = true,
        )
        assertTrue(decision.shouldClearUiActive)
        assertTrue(decision.missingTerminal)
        assertFalse(decision.establishesValidatedReady)
        assertTrue(decision.logMessage!!.contains("not validating"))
    }

    @Test
    fun notStuck_noClear() {
        val decision = CompileWatchdogLogic.evaluateStuckAtComplete(
            ppuActive = true,
            ppuPercent = 80,
            moduleDone = 4,
            moduleTotal = 9,
            jobMatches = true,
        )
        assertFalse(decision.shouldClearUiActive)
        assertFalse(decision.establishesValidatedReady)
    }
}
