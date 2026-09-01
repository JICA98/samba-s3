package com.zenithblue.sambas3.ui.controller

import com.zenithblue.sambas3.input.ControllerFamily
import com.zenithblue.sambas3.input.ControllerLayoutResolver
import com.zenithblue.sambas3.input.LogicalControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class ControllerHotspotLayoutTest {

    @Test
    fun keyboardMapContainsEverySourceKeyExactlyOnce() {
        val map = SvgRegionRegistry.decodeKeyboard(asset("controller_keyboard_regions.json"))
        assertEquals(104, map.regions.size)
        assertEquals(104, map.regions.map { it.code }.toSet().size)
        assertTrue(map.regions.any { it.code == "KeyW" && it.bounds.left == 188f })
        assertTrue(map.regions.any { it.code == "NumpadEnter" && it.bounds.bottom == 406f })
        assertTrue(map.regions.all { it.bounds.left >= 0f && it.bounds.top >= 0f })
    }

    @Test
    fun ds3MapMatchesAllSourceButtonGroups() {
        val map = SvgRegionRegistry.decodeController(asset("controller_ds3_regions.json"))
        assertEquals(ControllerLayoutResolver.GAMEPAD_HOTSPOTS, map.regions.map { it.id }.toSet())
        assertEquals(17, map.regions.size)
        assertTrue(map.regions.all { it.bounds.left >= 0f && it.bounds.top >= 0f })
        assertTrue(map.regions.all { it.bounds.right <= map.viewBox.width && it.bounds.bottom <= map.viewBox.height })
    }

    @Test
    fun fitTransformRoundTripsSourceCoordinatesAndKeepsLetterboxOutside() {
        val map = SvgRegionRegistry.decodeController(asset("controller_ds3_regions.json"))
        val transform = SvgViewportTransform(map.viewBox, contentWidth = 1200f, contentHeight = 1200f)
        val source = SvgScreenPoint(614f, 218f)
        val screen = transform.sourceToScreen(source)
        val roundTrip = transform.screenToSource(screen)
        assertTrue(roundTrip != null)
        assertTrue(abs(roundTrip!!.x - source.x) < 0.01f)
        assertTrue(abs(roundTrip.y - source.y) < 0.01f)
        assertEquals(null, transform.screenToSource(SvgScreenPoint(1f, 1f)))
    }

    @Test
    fun sourceRegionsMapToLogicalControlsWithoutFamilySpecificAliases() {
        val families = listOf(
            ControllerFamily.PLAYSTATION,
            ControllerFamily.XBOX,
            ControllerFamily.NINTENDO,
            ControllerFamily.GENERIC_GAMEPAD,
        )
        for (family in families) {
            for (control in LogicalControl.entries) {
                assertTrue("round-trip failed for $family / $control", ControllerHotspotLayout.roundTripHotspot(control, family))
            }
            assertEquals("btn_cross", ControllerLayoutResolver.hotspotForLogical(LogicalControl.CROSS, family))
        }
    }

    @Test
    fun keyboardSourceCodesMapOnlyMappedControls() {
        assertEquals(LogicalControl.DPAD_UP, ControllerHotspotLayout.logicalForHotspot("KeyW", ControllerFamily.KEYBOARD))
        assertEquals(LogicalControl.DPAD_UP, ControllerHotspotLayout.logicalForHotspot("ArrowUp", ControllerFamily.KEYBOARD))
        assertEquals(LogicalControl.START, ControllerHotspotLayout.logicalForHotspot("Enter", ControllerFamily.KEYBOARD))
        assertEquals(null, ControllerHotspotLayout.logicalForHotspot("KeyT", ControllerFamily.KEYBOARD))
        assertFalse(ControllerLayoutResolver.KEYBOARD_HOTSPOTS.contains("KeyT"))
    }

    private fun asset(name: String): String = listOf(
        File("app/src/main/assets/controllers/$name"),
        File("../app/src/main/assets/controllers/$name"),
        File("src/main/assets/controllers/$name"),
    ).first { it.isFile }.readText()
}
