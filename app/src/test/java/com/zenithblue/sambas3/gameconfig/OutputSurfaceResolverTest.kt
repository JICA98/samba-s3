package com.zenithblue.sambas3.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputSurfaceResolverTest {
    @Test
    fun noSelectionPreservesLayoutSizedSurface() {
        assertNull(OutputSurfaceResolver.resolve(null, "20:9", 19.8 / 9.0))
    }

    @Test
    fun selectedHeightsProduceAspectAwareSurfaceBufferDimensions() {
        assertEquals(OutputSurfaceSize(800, 360), OutputSurfaceResolver.resolve(360, "20:9", 16.0 / 9.0))
        assertEquals(OutputSurfaceSize(1200, 540), OutputSurfaceResolver.resolve(540, "20:9", 16.0 / 9.0))
        assertEquals(OutputSurfaceSize(1680, 720), OutputSurfaceResolver.resolve(720, "21:9", 16.0 / 9.0))
        assertEquals(OutputSurfaceSize(2400, 1080), OutputSurfaceResolver.resolve(1080, "20:9", 16.0 / 9.0))
    }

    @Test
    fun nativeAspectUsesCurrentWindowRatio() {
        assertEquals(OutputSurfaceSize(880, 360), OutputSurfaceResolver.resolve(360, "Native", 22.0 / 9.0))
    }
}
