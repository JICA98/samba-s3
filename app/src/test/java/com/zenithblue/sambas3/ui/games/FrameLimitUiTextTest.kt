package com.zenithblue.sambas3.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLimitUiTextTest {
    @Test
    fun infinite_is_presented_as_uncapped_without_changing_engine_value() {
        val copy = frameLimitUiText("Infinite")
        assertEquals("Uncapped", copy.title)
        assertTrue(copy.description.contains("ceiling"))
    }

    @Test
    fun numeric_limits_include_fps_units() {
        assertEquals("30 FPS", frameLimitUiText("30").title)
        assertEquals("60 FPS", frameLimitUiText("60").title)
        assertEquals("120 FPS", frameLimitUiText("120").title)
    }

    @Test
    fun auto_is_marked_recommended() {
        assertTrue(frameLimitUiText("Auto").title.contains("recommended"))
    }
}
