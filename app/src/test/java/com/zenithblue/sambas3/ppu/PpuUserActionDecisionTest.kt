package com.zenithblue.sambas3.ppu

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuUserActionDecisionTest {

    private fun inputs(
        pre: PreRuntimePpuState,
        rt: RuntimePpuState,
        validated: Boolean = false,
        install: Boolean = false,
        prelaunch: Boolean = false,
        runtime: Boolean = false,
        waiting: Boolean = false,
    ) = PpuActionInputs(pre, rt, validated, install, prelaunch, runtime, waiting)

    @Test
    fun notDone_mapsToInstallPreparation() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.NOT_DONE, RuntimePpuState.NOT_STARTED)
        )
        assertEquals(PpuUserAction.REBUILD_INSTALL_PPU, action)
        assertFalse(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun invalidated_mapsToInstallPreparation() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.INVALIDATED, RuntimePpuState.NOT_STARTED)
        )
        assertEquals(PpuUserAction.REBUILD_INSTALL_PPU, action)
    }

    @Test
    fun preFailed_mapsToInstallRetry() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.FAILED, RuntimePpuState.NOT_STARTED)
        )
        assertEquals(PpuUserAction.REBUILD_INSTALL_PPU, action)
    }

    @Test
    fun readyNotStarted_mapsToRuntimePreparation() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.NOT_STARTED)
        )
        assertEquals(PpuUserAction.PREPARE_RUNTIME, action)
        assertFalse(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun readyRuntimeFailed_mapsToRuntimePreparationRetry() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.FAILED)
        )
        assertEquals(PpuUserAction.RETRY_RUNTIME_PREPARATION, action)
        assertFalse(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun kotlinPreparedIdleWithoutFrameValidation_mapsToStart() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.IDLE_AFTER_COMPILE, validated = false)
        )
        assertEquals(PpuUserAction.START, action)
        assertTrue(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun validatedIdle_mapsToStart() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.IDLE_AFTER_COMPILE, validated = true)
        )
        assertEquals(PpuUserAction.START, action)
        assertTrue(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun activeInstallJob_mapsToWait() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.NOT_STARTED, install = true)
        )
        assertEquals(PpuUserAction.WAIT_FOR_ACTIVE_JOB, action)
    }

    @Test
    fun activePrelaunchOrRuntime_mapsToWait() {
        assertEquals(
            PpuUserAction.WAIT_FOR_ACTIVE_JOB,
            PpuUserActionDecision.decide(
                inputs(PreRuntimePpuState.READY, RuntimePpuState.COMPILING, prelaunch = true)
            )
        )
        assertEquals(
            PpuUserAction.WAIT_FOR_ACTIVE_JOB,
            PpuUserActionDecision.decide(
                inputs(PreRuntimePpuState.READY, RuntimePpuState.NOT_STARTED, runtime = true)
            )
        )
        assertEquals(
            PpuUserAction.WAIT_FOR_ACTIVE_JOB,
            PpuUserActionDecision.decide(
                inputs(PreRuntimePpuState.READY, RuntimePpuState.NOT_STARTED, waiting = true)
            )
        )
    }

    @Test
    fun inProgressWithoutLiveJob_mapsToInstallRetry() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.IN_PROGRESS, RuntimePpuState.NOT_STARTED)
        )
        assertEquals(PpuUserAction.REBUILD_INSTALL_PPU, action)
        assertFalse(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun inProgressWithLiveInstall_mapsToWait() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.IN_PROGRESS, RuntimePpuState.NOT_STARTED, install = true)
        )
        assertEquals(PpuUserAction.WAIT_FOR_ACTIVE_JOB, action)
    }

    @Test
    fun compilingWithoutLiveJob_mapsToRuntimeRetry() {
        val action = PpuUserActionDecision.decide(
            inputs(PreRuntimePpuState.READY, RuntimePpuState.COMPILING)
        )
        assertEquals(PpuUserAction.RETRY_RUNTIME_PREPARATION, action)
        assertFalse(PpuUserActionDecision.canEnterRealBoot(action))
    }

    @Test
    fun onlyStartCanEnterRealBoot() {
        PpuUserAction.entries.forEach {
            assertEquals(it == PpuUserAction.START, PpuUserActionDecision.canEnterRealBoot(it))
        }
    }
}
