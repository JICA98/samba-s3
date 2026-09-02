package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshBootFrameValidatorTest {

    @Test
    fun bootNoErrorsWithoutFrames_notValidated() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 1L, runtimePpuActive = false
        )
        assertEquals(FreshBootFramePhase.WaitingForFirstFrame, state.phase)
        assertFalse(state.isValidated)
        state = FreshBootFrameValidator.onFrameProbe(state, copied = false, running = true, surfaceGeneration = 1L)
        assertFalse(state.isValidated)
        assertEquals(0, state.stableSamples)
    }

    @Test
    fun runningWithRuntimePpuActive_stillWaiting() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 2L, runtimePpuActive = true
        )
        assertEquals(FreshBootFramePhase.WaitingForRuntimePpu, state.phase)
        state = FreshBootFrameValidator.onFrameProbe(state, copied = true, running = true, surfaceGeneration = 2L)
        assertEquals(FreshBootFramePhase.WaitingForRuntimePpu, state.phase)
        assertFalse(state.isValidated)
    }

    @Test
    fun runtimeTerminalThenOneFailedProbe_stillWaiting() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 3L, runtimePpuActive = true
        )
        state = FreshBootFrameValidator.onRuntimePpuTerminal(state, 3L)
        assertEquals(FreshBootFramePhase.WaitingForFirstFrame, state.phase)
        state = FreshBootFrameValidator.onFrameProbe(state, copied = false, running = true, surfaceGeneration = 3L)
        assertEquals(0, state.stableSamples)
        assertFalse(state.isValidated)
    }

    @Test
    fun threeStableProbes_validated() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 4L, runtimePpuActive = false
        )
        repeat(3) {
            state = FreshBootFrameValidator.onFrameProbe(state, copied = true, running = true, surfaceGeneration = 4L)
        }
        assertTrue(state.isValidated)
        assertEquals(FreshBootFramePhase.Validated, state.phase)
    }

    @Test
    fun surfaceGenerationChange_restartsProbeSequence() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 5L, runtimePpuActive = false
        )
        state = FreshBootFrameValidator.onFrameProbe(state, copied = true, running = true, surfaceGeneration = 5L)
        state = FreshBootFrameValidator.onFrameProbe(state, copied = true, running = true, surfaceGeneration = 5L)
        assertEquals(2, state.stableSamples)
        state = FreshBootFrameValidator.onFrameProbe(state, copied = true, running = true, surfaceGeneration = 6L)
        assertEquals(0, state.stableSamples)
        assertEquals(6L, state.surfaceGeneration)
        assertEquals(FreshBootFramePhase.WaitingForFirstFrame, state.phase)
    }

    @Test
    fun frameTimeout_failed() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 7L, runtimePpuActive = false
        )
        state = FreshBootFrameValidator.onTimeout(state)
        assertTrue(state.isFailed)
        assertEquals("frame-timeout", state.failureReason)
        assertFalse(state.isValidated)
    }

    @Test
    fun timeoutWhileWaitingForRuntimePpu_ignored() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = true, surfaceGeneration = 8L, runtimePpuActive = true
        )
        val after = FreshBootFrameValidator.onTimeout(state)
        assertEquals(FreshBootFramePhase.WaitingForRuntimePpu, after.phase)
        assertFalse(after.isFailed)
    }

    @Test
    fun bootError_failedWithoutValidation() {
        var state = FreshBootFrameValidator.bootRequested()
        state = FreshBootFrameValidator.bootReturned(
            state, noErrors = false, surfaceGeneration = 9L, runtimePpuActive = false
        )
        assertTrue(state.isFailed)
        assertFalse(state.isValidated)
    }
}
