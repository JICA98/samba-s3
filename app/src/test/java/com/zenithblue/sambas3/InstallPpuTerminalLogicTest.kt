package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstallPpuTerminalLogicTest {

    private fun ppuEvent(
        phase: Int,
        jobId: Long,
        value: Long = 0,
        max: Long = 100,
        origin: Int = RPCSX.COMPILE_ORIGIN_INSTALL,
        titleId: String? = "BLUS30443",
        moduleDone: Int = 233,
        moduleTotal: Int = 233,
    ) = CompileProgressBridge.NativeEvent(
        RPCSX.COMPILE_DOMAIN_PPU,
        phase,
        origin,
        jobId,
        value,
        max,
        "Progress",
        titleId,
        1,
        1,
        moduleDone,
        moduleTotal,
    )

    @Before
    fun setUp() {
        CompileProgressBridge.clearForTest()
    }

    @Test
    fun completedMatchingTitleAndJob_marksReady() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.COMPLETED,
            terminalTitleId = "BLUS30443",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertTrue(d.markPreRuntimeReady)
        assertEquals("completed", d.reason)
    }

    @Test
    fun failed_neverMarksReady() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.FAILED,
            terminalTitleId = "BLUS30443",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("failed", d.reason)
    }

    @Test
    fun canceled_neverMarksReady() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.CANCELED,
            terminalTitleId = "BLUS30443",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("canceled", d.reason)
    }

    @Test
    fun ppuActiveFalseAlone_withNone_neverMarksReady() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.NONE,
            terminalTitleId = "BLUS30443",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("outcome_none", d.reason)
    }

    @Test
    fun wrongTitle_ignored() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.COMPLETED,
            terminalTitleId = "BLUS31584",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("wrong_title", d.reason)
    }

    @Test
    fun staleWrongJob_ignored() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.COMPLETED,
            terminalTitleId = "BLUS30443",
            terminalJobId = 99L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("wrong_job", d.reason)
    }

    @Test
    fun stepMore_neverMarksReady() {
        val d = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = false,
            outcome = CompileOutcome.STEP_MORE,
            terminalTitleId = "BLUS30443",
            terminalJobId = 42L,
            expectedTitleId = "BLUS30443",
            expectedJobId = 42L,
        )
        assertFalse(d.markPreRuntimeReady)
        assertEquals("step_more", d.reason)
    }

    @Test
    fun bridge_installCompleted_preservesOutcomeAndTitle() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 7, 0, titleId = "BLUS30443"))
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        assertEquals(CompileOutcome.NONE, CompileProgressBridge.installState.value.outcome)
        assertEquals(7L, CompileProgressBridge.installState.value.jobId)

        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 7, 100, titleId = "BLUS30443"))
        val st = CompileProgressBridge.installState.value
        assertFalse(st.ppuActive)
        assertEquals(CompileOutcome.COMPLETED, st.outcome)
        assertEquals("BLUS30443", st.titleId)
        assertEquals(7L, st.jobId)

        val decision = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = st.ppuActive,
            outcome = st.outcome,
            terminalTitleId = st.titleId,
            terminalJobId = st.jobId,
            expectedTitleId = "BLUS30443",
            expectedJobId = 7L,
        )
        assertTrue(decision.markPreRuntimeReady)
    }

    @Test
    fun bridge_installFailed_preservesFailedOutcome_notReady() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 8, 0, titleId = "BLUS30443"))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_FAILED, 8, 40, titleId = "BLUS30443", moduleDone = 99, moduleTotal = 233))
        val st = CompileProgressBridge.installState.value
        assertFalse(st.ppuActive)
        assertEquals(CompileOutcome.FAILED, st.outcome)
        assertEquals("BLUS30443", st.titleId)
        assertEquals(8L, st.jobId)

        val decision = InstallPpuTerminalLogic.decide(
            installPpuWasSeen = true,
            ppuActive = st.ppuActive,
            outcome = st.outcome,
            terminalTitleId = st.titleId,
            terminalJobId = st.jobId,
            expectedTitleId = "BLUS30443",
            expectedJobId = 8L,
        )
        assertFalse(decision.markPreRuntimeReady)
    }

    @Test
    fun bridge_staleTerminal_doesNotClearNewerJob() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 10, 0, titleId = "BLUS30443"))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 10, 20, titleId = "BLUS30443"))
        // Stale terminal for old job 9 must be ignored
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 9, 100, titleId = "BLUS31584"))
        val st = CompileProgressBridge.installState.value
        assertTrue(st.ppuActive)
        assertEquals(CompileOutcome.NONE, st.outcome)
        assertEquals("BLUS30443", st.titleId)
        assertEquals(10L, st.jobId)
    }

    @Test
    fun bridge_canceled_preservesCanceled() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 11, 0, titleId = "BLUS30443"))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_CANCELED, 11, titleId = "BLUS30443"))
        val st = CompileProgressBridge.installState.value
        assertFalse(st.ppuActive)
        assertEquals(CompileOutcome.CANCELED, st.outcome)
        assertFalse(
            InstallPpuTerminalLogic.decide(
                installPpuWasSeen = true,
                ppuActive = st.ppuActive,
                outcome = st.outcome,
                terminalTitleId = st.titleId,
                terminalJobId = st.jobId,
                expectedTitleId = "BLUS30443",
                expectedJobId = 11L,
            ).markPreRuntimeReady
        )
    }
}
