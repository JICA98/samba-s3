package com.zenithblue.sambas3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompilationMonitorLogicTest {

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
}
