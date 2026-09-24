package com.zenithblue.sambas3

import com.zenithblue.sambas3.patch.PatchFastMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchFastModeTest {
    @Test
    fun curatedFastPatchesForTlou() {
        val patches = PatchFastMode.fastPatchNamesForTitle("BCUS98174")
        assertTrue(patches.contains("Disable in-built MLAA"))
        assertTrue(patches.contains("Disable Motion Blur"))
        assertTrue(patches.contains("Disable SSAO"))
        assertTrue(patches.contains("Skip Intro"))
        // Confirmed: glitch-causing patches must NOT be included in fast mode for TLOU
        assertTrue(!patches.contains("Disable Depth of Field"))
        assertTrue(!patches.contains("Disable Bloom"))
        assertTrue(!patches.contains("Enable GPU Lighting"))
    }

    @Test
    fun curatedFastPatchesCaseInsensitive() {
        val upper = PatchFastMode.fastPatchNamesForTitle("bcus98174")
        val expected = setOf("Disable in-built MLAA", "Disable Motion Blur", "Disable SSAO", "Skip Intro")
        assertEquals(expected, upper)
    }

    @Test
    fun curatedFastPatchesForUncharted2() {
        val patches = PatchFastMode.fastPatchNamesForTitle("BCUS98123")
        assertTrue(patches.contains("Disable in-built MLAA"))
        assertTrue(patches.contains("Disable Motion Blur"))
        assertTrue(patches.contains("Skip Intro"))
    }

    @Test
    fun curatedFastPatchesForGodOfWar3() {
        val testIds = listOf("BCUS98111", "BCES00510", "BCES00799", "BCJS37001", "BCAS25003", "BCKS15003")
        val expected = setOf("Disable MLAA", "Disable Motion Blur", "Skip intro")
        for (id in testIds) {
            assertTrue("Expected $id to support Fast Mode", PatchFastMode.isFastModeSupported(id))
            val patches = PatchFastMode.fastPatchNamesForTitle(id)
            assertEquals(expected, patches)
            assertTrue(
                "Expected empty Fast Mode settings for $id",
                PatchFastMode.fastModeSettingsForTitle(id).isEmpty()
            )
        }
    }

    @Test
    fun curatedFastPatchesForGtaV() {
        val testIds = listOf(
            "BLJM61019", "BLUS31156", "BLES01807",
            "NPUB31156", "NPEB01807", "NPJB00517",
            "NPUB31154", "NPJB00516", "NPEB01283",
        )
        for (id in testIds) {
            assertTrue("Expected $id to support Fast Mode", PatchFastMode.isFastModeSupported(id))
            val patches = PatchFastMode.fastPatchNamesForTitle(id)
            assertTrue("Expected $id patches to contain Skip Rockstar Boot Logo", patches.contains("Skip Rockstar Boot Logo"))
            val settings = PatchFastMode.fastModeSettingsForTitle(id)
            assertEquals("1", settings["Video@@Driver Wake-Up Delay"])
            assertTrue(!settings.containsKey("Core@@SPU Block Size"))
        }
    }

    @Test
    fun unsupportedTitleIdReturnsFalseAndEmptyPatches() {
        val unsupportedIds = listOf("UNKNOWN123", "BLUS00000", "TEST12345", "", null)
        for (id in unsupportedIds) {
            assertTrue("Expected $id to NOT support Fast Mode", !PatchFastMode.isFastModeSupported(id))
            val patches = PatchFastMode.fastPatchNamesForTitle(id)
            assertTrue("Expected empty patches for $id", patches.isEmpty())
            assertTrue(
                "Expected empty Fast Mode settings for $id",
                PatchFastMode.fastModeSettingsForTitle(id).isEmpty(),
            )
        }
    }

    @Test
    fun staleOrUnsupportedTitleCannotEnableFastMode() = kotlinx.coroutines.runBlocking {
        PatchFastMode.enabledByTitle.clear()
        val result = PatchFastMode.setFastModeEnabled("UNKNOWN123", true)
        assertTrue("Enabling Fast Mode for unsupported title must fail", !result)
        val enabled = PatchFastMode.isFastModeEnabled("UNKNOWN123")
        assertTrue("Fast Mode must be false for unsupported title", !enabled)
        assertTrue(
            "Unsupported title must not persist Fast Mode",
            !PatchFastMode.isFastModeEnabledSync(null, "UNKNOWN123"),
        )
    }

    @Test
    fun gtaVFastModePersistsWithoutPatchesAndDoesNotLeak() = kotlinx.coroutines.runBlocking {
        PatchFastMode.enabledByTitle.clear()
        val enabled = PatchFastMode.setFastModeEnabled("BLJM61019", true)
        assertTrue("GTA V Fast Mode must enable even if patches are absent", enabled)
        assertTrue(PatchFastMode.isFastModeEnabledSync(null, "BLJM61019"))
        assertTrue(
            "Unsupported game must not inherit GTA V Fast Mode",
            !PatchFastMode.isFastModeEnabledSync(null, "BLUS00000"),
        )
        PatchFastMode.setFastModeEnabled("BLJM61019", false)
        assertTrue(!PatchFastMode.isFastModeEnabledSync(null, "BLJM61019"))
    }

    @Test
    fun tlouFastModeHasNoGtaSettings() {
        val settings = PatchFastMode.fastModeSettingsForTitle("BCUS98174")
        assertTrue("TLOU Fast Mode must not inherit GTA V engine settings", settings.isEmpty())
    }
}
