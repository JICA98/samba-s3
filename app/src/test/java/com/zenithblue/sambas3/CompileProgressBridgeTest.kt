package com.zenithblue.sambas3

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CompileProgressBridgeTest {

    private fun ppuEvent(phase: Int, jobId: Long, value: Long = 0, max: Long = 100, msg: String? = "Progress: file 1 of 2, module 1 of 3 (1m remaining)", origin: Int = RPCSX.COMPILE_ORIGIN_RUNTIME, titleId: String? = null, fileDone: Int = 1, fileTotal: Int = 2, moduleDone: Int = 1, moduleTotal: Int = 3) =
        CompileProgressBridge.NativeEvent(RPCSX.COMPILE_DOMAIN_PPU, phase, origin, jobId, value, max, msg, titleId, fileDone, fileTotal, moduleDone, moduleTotal)

    private fun shaderEvent(phase: Int, jobId: Long, origin: Int = RPCSX.COMPILE_ORIGIN_RUNTIME, titleId: String? = null) =
        CompileProgressBridge.NativeEvent(RPCSX.COMPILE_DOMAIN_SHADER, phase, origin, jobId, 0, 0, "Compiling shaders", titleId, 0, 0, 0, 0)

    @Before
    fun setUp() {
        CompileProgressBridge.clearForTest()
    }

    @Test
    fun ppuBeginSetsActiveAndProgress() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 1, 10))
        val s1 = CompileProgressBridge.state.value
        assertTrue(s1.ppuActive)
        assertEquals(10, s1.ppuPercent)
        assertEquals(1, s1.fileDone)

        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 1, 50, msg = "Progress: file 2 of 2, module 2 of 3 (30s remaining)"))
        val s2 = CompileProgressBridge.state.value
        assertTrue(s2.ppuActive)
        assertEquals(50, s2.ppuPercent)
        assertEquals("Progress: file 2 of 2, module 2 of 3 (30s remaining)", s2.ppuMsg)
    }

    @Test
    fun duplicatePpuBeginIgnored() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 10, 5))
        val s1 = CompileProgressBridge.state.value
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 10, 5))
        val s2 = CompileProgressBridge.state.value
        // State unchanged, still active with same job
        assertEquals(s1, s2)
        assertTrue(s2.ppuActive)
    }

    @Test
    fun ppuDuplicateTerminalIgnored() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 11, 0))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 11))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        // Duplicate terminal for same job should be ignored (already cleared)
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 11))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
    }

    @Test
    fun outOfOrderTerminalIgnored() {
        // Terminal for unknown job before BEGIN should be ignored
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 99))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        // Now real BEGIN
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 100, 0))
        assertTrue(CompileProgressBridge.state.value.ppuActive)
    }

    @Test
    fun twoConcurrentShaderJobs() {
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 1))
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 2))
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        // Complete one, still active
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_COMPLETED, 1))
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        // Complete second, inactive
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_COMPLETED, 2))
        assertFalse(CompileProgressBridge.state.value.shaderActive)
        assertEquals(0, CompileProgressBridge.state.value.activeDomainCount)
    }

    @Test
    fun shaderDuplicateBeginIgnored() {
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 5))
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 5))
        // Still one job, completing once should clear
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_COMPLETED, 5))
        assertFalse(CompileProgressBridge.state.value.shaderActive)
        // Duplicate terminal ignored
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_COMPLETED, 5))
        assertFalse(CompileProgressBridge.state.value.shaderActive)
    }

    @Test
    fun ppu100WithoutTerminalStillActive() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 20, 0))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 20, 100, msg = "Progress: file 78 of 78, module 33 of 33 (done)"))
        val s = CompileProgressBridge.state.value
        assertTrue(s.ppuActive)
        assertEquals(100, s.ppuPercent)
        // Only explicit terminal clears
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 20))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
    }

    @Test
    fun ppuCanceledWhileShaderRemains() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 30, 10))
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 31))
        assertEquals(2, CompileProgressBridge.state.value.activeDomainCount)
        // Cancel PPU, shader still active
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_CANCELED, 30))
        val s = CompileProgressBridge.state.value
        assertFalse(s.ppuActive)
        assertTrue(s.shaderActive)
        assertEquals(1, s.activeDomainCount)
        // Now cancel shader, all inactive
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_CANCELED, 31))
        assertFalse(CompileProgressBridge.state.value.isActive)
    }

    @Test
    fun installOriginSuppressed() {
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 40, 10, origin = RPCSX.COMPILE_ORIGIN_INSTALL))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        assertFalse(CompileProgressBridge.state.value.isActive)
        // Install state should be active for Kotlin UI pre-compile
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        assertEquals(10, CompileProgressBridge.installState.value.ppuPercent)
        // Even progress suppressed for runtime but updates install
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 40, 50, origin = RPCSX.COMPILE_ORIGIN_INSTALL))
        assertFalse(CompileProgressBridge.state.value.ppuActive)
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        assertEquals(50, CompileProgressBridge.installState.value.ppuPercent)
        // Complete install PPU
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 40, origin = RPCSX.COMPILE_ORIGIN_INSTALL))
        assertFalse(CompileProgressBridge.installState.value.ppuActive)
        // Runtime after still works
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 41, 0))
        assertTrue(CompileProgressBridge.state.value.ppuActive)
    }

    @Test
    fun installPpuKotlinUiDirect() {
        // Direct start of pre PPU via Kotlin UI — game imported
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 80, 5, origin = RPCSX.COMPILE_ORIGIN_INSTALL, msg = "Progress: file 1 of 10, module 1 of 5 (2m remaining)"))
        val install = CompileProgressBridge.installState.value
        assertTrue(install.ppuActive)
        assertEquals("Progress: file 1 of 10, module 1 of 5 (2m remaining)", install.ppuMsg)
        // Simulate progress update in Kotlin install UI
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 80, 50, origin = RPCSX.COMPILE_ORIGIN_INSTALL, msg = "Progress: file 5 of 10, module 3 of 5 (1m remaining)"))
        assertEquals(50, CompileProgressBridge.installState.value.ppuPercent)
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        // Runtime also can be active concurrently via separate FGS (2000 vs 3000)
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 81, 0))
        assertTrue(CompileProgressBridge.state.value.ppuActive)
        assertTrue(CompileProgressBridge.installState.value.ppuActive)
        assertEquals(2, CompileProgressBridge.state.value.activeDomainCount + (if (CompileProgressBridge.installState.value.ppuActive) 1 else 0))
    }

    @Test
    fun shaderCancelAllWithJobZero() {
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 1))
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 2))
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 3))
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        // ProgramStateCache.clear emits CANCELED with jobId 0 to clear pending
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_CANCELED, 0))
        assertFalse(CompileProgressBridge.state.value.shaderActive)
    }

    @Test
    fun activeDomainCountGate() {
        assertEquals(0, CompileProgressBridge.state.value.activeDomainCount)
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 50))
        assertEquals(1, CompileProgressBridge.state.value.activeDomainCount)
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 51))
        assertEquals(2, CompileProgressBridge.state.value.activeDomainCount)
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 50))
        assertEquals(1, CompileProgressBridge.state.value.activeDomainCount)
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_COMPLETED, 51))
        assertEquals(0, CompileProgressBridge.state.value.activeDomainCount)
    }

    @Test
    fun shaderIndeterminateNoEta() {
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 60))
        val s = CompileProgressBridge.state.value
        assertTrue(s.shaderActive)
        // Bridge should keep shader indeterminate (no value/max)
        // No ETA assumption — just ensure state doesn't have PPU progress
        assertFalse(s.ppuActive)
    }

    @Test
    fun ppuProgressWithoutBeginTreatedAsActive() {
        // Missed BEGIN, first PROGRESS should still activate (defensive)
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_PROGRESS, 70, 20, msg = "Progress: file 5 of 10"))
        assertTrue(CompileProgressBridge.state.value.ppuActive)
        assertEquals(20, CompileProgressBridge.state.value.ppuPercent)
    }

    @Test
    fun shaderFailedIsTerminal() {
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_BEGIN, 77))
        assertTrue(CompileProgressBridge.state.value.shaderActive)
        assertTrue(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_SHADER, 77))
        CompileProgressBridge.injectForTest(shaderEvent(RPCSX.COMPILE_PHASE_FAILED, 77))
        assertFalse(CompileProgressBridge.state.value.shaderActive)
        assertFalse(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_SHADER, 77))
    }

    @Test
    fun isRuntimeJobActiveTracksPpuAndShader() {
        assertFalse(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_PPU, 1))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_BEGIN, 1, 10))
        assertTrue(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_PPU, 1))
        assertFalse(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_PPU, 2))
        CompileProgressBridge.injectForTest(ppuEvent(RPCSX.COMPILE_PHASE_COMPLETED, 1))
        assertFalse(CompileProgressBridge.isRuntimeJobActive(RPCSX.COMPILE_DOMAIN_PPU, 1))
    }

    @Test
    fun compileProgressCallbackJniDescriptorMatchesNative() {
        val method = RPCSX.CompileProgressCallback::class.java.declaredMethods
            .single { it.name == "onEvent" }
        assertEquals(
            RPCSX.COMPILE_PROGRESS_ON_EVENT_JNI_DESCRIPTOR,
            jniDescriptor(method)
        )
        // The previous broken native lookup used IIIJJ (missing max's J) and GetMethodID failed.
        assertFalse(RPCSX.COMPILE_PROGRESS_ON_EVENT_JNI_DESCRIPTOR.contains("(IIIJJLjava/lang/String;"))
        assertTrue(RPCSX.COMPILE_PROGRESS_ON_EVENT_JNI_DESCRIPTOR.startsWith("(IIIJJJLjava/lang/String;"))
    }

    private fun jniDescriptor(method: java.lang.reflect.Method): String {
        fun desc(c: Class<*>): String = when {
            c == Void.TYPE -> "V"
            c == Integer.TYPE -> "I"
            c == java.lang.Long.TYPE -> "J"
            c == java.lang.Boolean.TYPE -> "Z"
            c == String::class.java -> "Ljava/lang/String;"
            c.isArray -> "[" + desc(c.componentType!!)
            else -> "L" + c.name.replace('.', '/') + ";"
        }
        return buildString {
            append('(')
            method.parameterTypes.forEach { append(desc(it)) }
            append(')')
            append(desc(method.returnType))
        }
    }
}
