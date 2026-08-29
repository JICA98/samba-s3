package com.zenithblue.sambas3

import com.zenithblue.sambas3.session.SessionStatePairing
import com.zenithblue.sambas3.session.SessionStateReconciliation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateReconciliationTest {
    @Test fun detectsActiveGameWhileStopped() {
        assertEquals("activeGame-present-while-stopped", SessionStateReconciliation.invalidPairing(SessionStatePairing(EmulatorState.Stopped, "/game")))
    }
    @Test fun detectsMissingActiveGameWhileAlive() {
        assertEquals("activeGame-missing-while-alive", SessionStateReconciliation.invalidPairing(SessionStatePairing(EmulatorState.Running, null)))
    }
    @Test fun acceptsConsistentPairings() {
        assertNull(SessionStateReconciliation.invalidPairing(SessionStatePairing(EmulatorState.Stopped, null)))
        assertNull(SessionStateReconciliation.invalidPairing(SessionStatePairing(EmulatorState.Running, "/game")))
    }
}
