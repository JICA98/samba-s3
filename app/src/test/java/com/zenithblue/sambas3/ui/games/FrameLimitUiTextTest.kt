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
        assertTrue(frameLimitUiText("120").title.startsWith("120 FPS"))
    }

    @Test
    fun mobile_presets_are_promoted_and_only_selected_legacy_value_is_retained() {
        val available = listOf("Off", "30", "50", "60", "120", "Display", "Auto", "PS3 Native", "Infinite")
        assertEquals(listOf("30", "60", "Infinite", "Auto"), frameLimitOptions(available, "Auto"))
        assertEquals(listOf("30", "60", "Infinite"), frameLimitOptions(available, "60"))
    }
}
