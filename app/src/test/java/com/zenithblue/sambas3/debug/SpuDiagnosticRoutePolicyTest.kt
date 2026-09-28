package com.zenithblue.sambas3.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpuDiagnosticRoutePolicyTest {
    @Test fun fixtureSkipsVideoReadinessAndTheGenericNoFrameWatchdog() {
        assertFalse(SpuDiagnosticRoutePolicy.usesFrameReadiness(isFixture = true))
        assertFalse(SpuDiagnosticRoutePolicy.usesNoFrameWatchdog(isFixture = true))
        assertTrue(SpuDiagnosticRoutePolicy.usesFrameReadiness(isFixture = false))
        assertTrue(SpuDiagnosticRoutePolicy.usesNoFrameWatchdog(isFixture = false))
    }

    @Test fun fixtureWaitIsBoundedAndTimeoutStartsAtTheDeclaredLimit() {
        assertFalse(SpuDiagnosticRoutePolicy.timedOut(isFixture = false, elapsedMs = Long.MAX_VALUE))
        assertFalse(SpuDiagnosticRoutePolicy.timedOut(isFixture = true, elapsedMs = SpuDiagnosticRoutePolicy.WAIT_TIMEOUT_MS - 1))
        assertTrue(SpuDiagnosticRoutePolicy.timedOut(isFixture = true, elapsedMs = SpuDiagnosticRoutePolicy.WAIT_TIMEOUT_MS))
    }
}
