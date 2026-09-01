package com.zenithblue.sambas3.input

import com.zenithblue.sambas3.ui.controller.SvgRegionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural checks for the real SVG assets and their source-derived region maps. */
class ControllerAssetsPresenceTest {

    private val assetsRoot: File
        get() = listOf(
            File("app/src/main/assets/controllers"),
            File("src/main/assets/controllers"),
        ).first { it.isDirectory }

    @Test
    fun allResolvedLayoutsHavePackagedArtworkAndRegions() {
        val ds3 = File(assetsRoot, "controller_ds3.svg")
        val keyboard = File(assetsRoot, "controller_keyboard.svg")
        assertTrue(ds3.isFile)
        assertTrue(keyboard.isFile)
        assertTrue(ds3.readText().contains("data-button=\"triangle\""))
        assertTrue(keyboard.readText().contains("data-code=\"KeyW\""))
        assertEquals(ControllerLayoutResolver.GAMEPAD_HOTSPOTS, SvgRegionRegistry.decodeController(File(assetsRoot, "controller_ds3_regions.json").readText()).regions.map { it.id }.toSet())
        assertEquals(104, SvgRegionRegistry.decodeKeyboard(File(assetsRoot, "controller_keyboard_regions.json").readText()).regions.size)
    }

    @Test
    fun runtimeSvgsContainNoBrowserRuntimeOrStatusCopy() {
        for (name in listOf("controller_ds3.svg", "controller_keyboard.svg")) {
            val text = File(assetsRoot, name).readText()
            assertFalse("$name must be static", text.contains("<script"))
            assertFalse("$name must not show source status", text.contains("PRESS ANY KEY"))
            assertFalse("$name must not show source status", text.contains("NO GAMEPAD DETECTED"))
        }
    }
}
