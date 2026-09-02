package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompilationMonitorLogicTest {

    private fun ppuEvent(
        phase: Int,
        jobId: Long,
        value: Long = 0,
        origin: Int = RPCSX.COMPILE_ORIGIN_RUNTIME,
        titleId: String? = "BLUS30443",
        msg: String? = "Progress",
    ) = CompileProgressBridge.NativeEvent(
        RPCSX.COMPILE_DOMAIN_PPU, phase, origin, jobId, value, 100, msg, titleId, 1, 2, 1, 3
    )

    @Before
    fun setUp() {
        CompileProgressBridge.clearForTest()
    }

    @Test
    fun stopWhenLiveDomainCountIsZero() {
        assertTrue(CompilationMonitorLogic.shouldStopAfterPromotion(0))
        assertFalse(CompilationMonitorLogic.shouldStopAfterPromotion(1))
        assertFalse(CompilationMonitorLogic.shouldStopAfterPromotion(2))
    }

    @Test
    fun staleBeginDoesNotKeepJobActive() {
        CompileProgressBridge.injectForTest(
            CompileProgressBridge.NativeEvent(
                RPCSX.COMPILE_DOMAIN_SHADER,
                RPCSX.COMPILE_PHASE_BEGIN,
                RPCSX.COMPILE_ORIGIN_RUNTIME,
                9,
                0, 0, "Compiling shaders", null, 0, 0, 0, 0
            )
        )
        CompileProgressBridge.injectForTest(
            CompileProgressBridge.NativeEvent(
                RPCSX.COMPILE_DOMAIN_SHADER,
                RPCSX.COMPILE_PHASE_COMPLETED,
                RPCSX.COMPILE_ORIGIN_RUNTIME,
                9,
                0, 0, null, null, 0, 0, 0, 0
            )
        )
        assertFalse(CompileProgressBridge.state.value.isActive)
        assertFalse(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_SHADER, 9))
        assertTrue(CompilationMonitorLogic.shouldStopAfterPromotion(CompileProgressBridge.state.value.activeDomainCount))
    }

    @Test
    fun prelaunchBeginKeepsMonitorActiveEvenIfRuntimeIdle() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 501, 5, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        assertTrue(CompileProgressBridge.prelaunchState.value.ppuActive)
        assertFalse(CompileProgressBridge.state.value.isActive)
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )
        assertTrue(projection.isActive)
        assertTrue(projection.prelaunchActive)
        assertFalse(
            CompilationMonitorLogic.shouldStopAfterPromotion(
                projection.runtime.activeDomainCount,
                projection.prelaunchActive,
            )
        )
        assertTrue(CompileProgressBridge.isMonitorJobActive(RPCSX.COMPILE_DOMAIN_PPU, 501, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
    }

    @Test
    fun prelaunchProgressUpdatesProjectionWithoutTouchingRuntimeState() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 502, 0, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 502, 55, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        assertEquals(55, CompileProgressBridge.prelaunchState.value.ppuPercent)
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )
        assertEquals(55, CompilationMonitorLogic.contentState(projection).ppuPercent)
    }

    @Test
    fun prelaunchTerminalAllowsStopWhenNoOtherDomainActive() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 503, 0, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 503, 100, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        assertFalse(CompileProgressBridge.prelaunchState.value.ppuActive)
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )
        assertTrue(
            CompilationMonitorLogic.shouldStopAfterPromotion(
                projection.runtime.activeDomainCount,
                projection.prelaunchActive,
            )
        )
    }

    @Test
    fun prelaunchTerminalDoesNotClearRuntimeShader() {
        CompileProgressBridge.injectForTest(
            CompileProgressBridge.NativeEvent(
                RPCSX.COMPILE_DOMAIN_SHADER, RPCSX.COMPILE_PHASE_BEGIN, RPCSX.COMPILE_ORIGIN_RUNTIME,
                88, 0, 0, "Compiling shaders", null, 0, 0, 0, 0
            )
        )
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 504, 0, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 504, 100, RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        assertFalse(CompileProgressBridge.prelaunchState.value.ppuActive)
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )
        assertFalse(
            CompilationMonitorLogic.shouldStopAfterPromotion(
                projection.runtime.activeDomainCount,
                projection.prelaunchActive,
            )
        )
        assertTrue(CompilationMonitorLogic.MonitorOwner.RUNTIME_SHADER in projection.activeOwners)
    }

    @Test
    fun installOriginDoesNotCreateRuntimeMonitorDuplicate() {
        assertTrue(CompilationMonitorLogic.shouldIgnoreInstallOrigin(RPCSX.COMPILE_ORIGIN_INSTALL))
        assertFalse(CompilationMonitorLogic.shouldIgnoreInstallOrigin(RPCSX.COMPILE_ORIGIN_PRELAUNCH))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 600, 10, RPCSX.COMPILE_ORIGIN_INSTALL))
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        assertFalse(CompileProgressBridge.prelaunchState.value.ppuActive)
        assertFalse(CompileProgressBridge.isMonitorJobActive(RPCSX.COMPILE_DOMAIN_PPU, 600, RPCSX.COMPILE_ORIGIN_INSTALL))
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.state.value,
            CompileProgressBridge.prelaunchState.value,
        )
        assertTrue(
            CompilationMonitorLogic.shouldStopAfterPromotion(
                projection.runtime.activeDomainCount,
                projection.prelaunchActive,
            )
        )
    }

    @Test
    fun notificationTitle_prefersPreparingRuntimePpuForPrelaunch() {
        val projection = CompilationMonitorLogic.project(
            CompileProgressBridge.CompileState(),
            CompileProgressBridge.CompileState(ppuActive = true, ppuPercent = 20),
        )
        val title = CompilationMonitorLogic.notificationTitle(
            projection,
            compilingPpu = "Compiling PPU",
            compilingShaders = "Compiling shaders",
            preparingRuntimePpu = "Preparing Runtime PPU",
        )
        assertEquals("Preparing Runtime PPU", title)
    }
}
