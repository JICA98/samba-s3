package com.zenithblue.sambas3.input

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural check that repo-local controller SVGs exist with required hotspot IDs.
 * Drives the same asset paths resolved by [ControllerLayoutResolver].
 */
class ControllerAssetsPresenceTest {

    private val assetsRoot: File
        get() {
            val candidates = listOf(
                File("app/src/main/assets/controllers"),
                File("src/main/assets/controllers"),
            )
            return candidates.first { it.isDirectory }
        }

    @Test
    fun allFamilyAssetsExistWithRequiredHotspots() {
        val families = listOf(
            ControllerFamily.PLAYSTATION,
            ControllerFamily.XBOX,
            ControllerFamily.NINTENDO,
            ControllerFamily.GENERIC_GAMEPAD,
            ControllerFamily.KEYBOARD,
        )
        for (family in families) {
            val layout = ControllerLayoutResolver.resolve(family)
            val fileName = layout.assetPath.substringAfterLast('/')
            val file = File(assetsRoot, fileName)
            assertTrue("missing asset for $family: ${file.absolutePath}", file.isFile)
            val text = file.readText()
            for (id in layout.hotspotIds) {
                assertTrue("$fileName missing hotspot id=$id", text.contains("id=\"$id\""))
            }
        }
    }

    @Test
    fun keyboardAssetIsNotAGamepadSilhouetteFile() {
        val kb = ControllerLayoutResolver.resolve(ControllerFamily.KEYBOARD)
        assertTrue(kb.assetPath.endsWith("controller_keyboard.svg"))
        val text = File(assetsRoot, "controller_keyboard.svg").readText()
        assertTrue(text.contains("KEYBOARD"))
        assertTrue(text.contains("id=\"key_w\""))
    }
}
