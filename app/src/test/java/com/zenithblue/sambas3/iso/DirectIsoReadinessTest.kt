package com.zenithblue.sambas3.iso

import com.zenithblue.sambas3.PreRuntimePpuState
import com.zenithblue.sambas3.RuntimePpuState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectIsoReadinessTest {

    @Test
    fun manufacturedReadyWithoutCache_isReset() {
        assertTrue(
            DirectIsoManager.shouldResetFakeReady(
                PreRuntimePpuState.READY,
                RuntimePpuState.IDLE_AFTER_COMPILE,
                hasCacheObjects = false,
            )
        )
    }

    @Test
    fun realCache_keepsReady() {
        assertFalse(
            DirectIsoManager.shouldResetFakeReady(
                PreRuntimePpuState.READY,
                RuntimePpuState.IDLE_AFTER_COMPILE,
                hasCacheObjects = true,
            )
        )
    }

    @Test
    fun honestNotDone_isNotReset() {
        assertFalse(
            DirectIsoManager.shouldResetFakeReady(
                PreRuntimePpuState.NOT_DONE,
                RuntimePpuState.NOT_STARTED,
                hasCacheObjects = false,
            )
        )
    }
}
