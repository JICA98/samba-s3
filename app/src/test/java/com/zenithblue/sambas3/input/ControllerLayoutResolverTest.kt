package com.zenithblue.sambas3.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerLayoutResolverTest {

    @Test
    fun playstationUsesPsAssetWithGamepadHotspots() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.PLAYSTATION)
        assertEquals(ControllerLayoutResolver.ASSET_PS, layout.assetPath)
        assertTrue(layout.hotspotIds.containsAll(setOf("btn_cross", "stick_left", "trigger_right", "btn_guide")))
        assertFalse(layout.assetPath.contains("keyboard"))
    }

    @Test
    fun xboxUsesXboxAsset() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.XBOX)
        assertEquals(ControllerLayoutResolver.ASSET_XBOX, layout.assetPath)
        assertTrue(layout.hotspotIds.contains("btn_a"))
    }

    @Test
    fun nintendoUsesSwitchAsset() {
        assertEquals(
            ControllerLayoutResolver.ASSET_SWITCH,
            ControllerLayoutResolver.resolve(ControllerFamily.NINTENDO).assetPath,
        )
    }

    @Test
    fun keyboardNeverResolvesToGamepadSkin() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.KEYBOARD)
        assertEquals(ControllerLayoutResolver.ASSET_KEYBOARD, layout.assetPath)
        assertTrue(layout.hotspotIds.contains("key_w"))
        assertFalse(layout.hotspotIds.contains("btn_cross"))
        assertFalse(layout.assetPath.contains("controller_ps"))
        assertFalse(layout.assetPath.contains("controller_generic"))
    }

    @Test
    fun unknownFallsBackToGeneric() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.UNKNOWN)
        assertEquals(ControllerLayoutResolver.ASSET_GENERIC, layout.assetPath)
        assertTrue(layout.hotspotIds.containsAll(ControllerLayoutResolver.GAMEPAD_HOTSPOTS))
    }

    @Test
    fun hotspotForLogicalMatchesFamilyFaceButtons() {
        assertEquals("btn_cross", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.PLAYSTATION))
        assertEquals("btn_a", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.XBOX))
        assertEquals("btn_b", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.NINTENDO))
        assertEquals("btn_a", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CIRCLE, ControllerFamily.NINTENDO))
    }

    @Test
    fun nintendoFaceHotspotsAreUniqueNoCollision() {
        val cross = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.NINTENDO)
        val circle = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CIRCLE, ControllerFamily.NINTENDO)
        val square = ControllerLayoutResolver.hotspotForLogical(LogicalControl.SQUARE, ControllerFamily.NINTENDO)
        val triangle = ControllerLayoutResolver.hotspotForLogical(LogicalControl.TRIANGLE, ControllerFamily.NINTENDO)
        assertEquals(setOf(cross, circle, square, triangle).size, 4)
        assertFalse(cross == circle)
    }
}
