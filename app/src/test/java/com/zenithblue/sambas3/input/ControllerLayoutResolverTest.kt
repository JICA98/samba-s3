package com.zenithblue.sambas3.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerLayoutResolverTest {

    @Test
    fun allGamepadFamiliesUseTheSuppliedDs3Artwork() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.PLAYSTATION)
        assertEquals(ControllerLayoutResolver.ASSET_PS, layout.assetPath)
        assertEquals(ControllerLayoutResolver.ASSET_DS3, layout.assetPath)
        assertTrue(layout.hotspotIds.containsAll(setOf("btn_cross", "stick_left", "btn_r2", "btn_guide")))
        assertFalse(layout.assetPath.contains("keyboard"))
    }

    @Test
    fun xboxUsesDs3ArtworkWithPhysicalLabelsSeparate() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.XBOX)
        assertEquals(ControllerLayoutResolver.ASSET_XBOX, layout.assetPath)
        assertEquals(ControllerLayoutResolver.ASSET_DS3, layout.assetPath)
        assertTrue(layout.physicalLabels.getValue("btn_cross").contains("Cross"))
    }

    @Test
    fun nintendoUsesDs3Artwork() {
        assertEquals(
            ControllerLayoutResolver.ASSET_DS3,
            ControllerLayoutResolver.resolve(ControllerFamily.NINTENDO).assetPath,
        )
    }

    @Test
    fun keyboardNeverResolvesToGamepadSkin() {
        val layout = ControllerLayoutResolver.resolve(ControllerFamily.KEYBOARD)
        assertEquals(ControllerLayoutResolver.ASSET_KEYBOARD, layout.assetPath)
        assertTrue(layout.hotspotIds.contains("KeyW"))
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
    fun hotspotForLogicalUsesTheSameDs3FaceButtonsForEveryGamepad() {
        assertEquals("btn_cross", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.PLAYSTATION))
        assertEquals("btn_cross", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.XBOX))
        assertEquals("btn_cross", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.NINTENDO))
        assertEquals("btn_circle", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CIRCLE, ControllerFamily.NINTENDO))
    }

    @Test
    fun keyboardLogicalHotspotsUseSourceDataCodes() {
        assertEquals("KeyW", ControllerLayoutResolver.hotspotForLogical(LogicalControl.DPAD_UP, ControllerFamily.KEYBOARD))
        assertEquals("Enter", ControllerLayoutResolver.hotspotForLogical(LogicalControl.START, ControllerFamily.KEYBOARD))
    }

    @Test
    fun gamepadFaceHotspotsAreUniqueNoCollision() {
        val cross = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, ControllerFamily.NINTENDO)
        val circle = ControllerLayoutResolver.hotspotForLogical(LogicalControl.CIRCLE, ControllerFamily.NINTENDO)
        val square = ControllerLayoutResolver.hotspotForLogical(LogicalControl.SQUARE, ControllerFamily.NINTENDO)
        val triangle = ControllerLayoutResolver.hotspotForLogical(LogicalControl.TRIANGLE, ControllerFamily.NINTENDO)
        assertEquals(4, setOf(cross, circle, square, triangle).size)
        assertFalse(cross == circle)
    }
}
