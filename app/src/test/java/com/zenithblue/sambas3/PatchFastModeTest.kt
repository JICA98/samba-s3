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
        assertTrue(patches.contains("Skip Intro"))
        // Confirmed: glitch-causing patches must NOT be included in fast mode for TLOU
        assertTrue(!patches.contains("Disable Depth of Field"))
        assertTrue(!patches.contains("Disable Bloom"))
        assertTrue(!patches.contains("Enable GPU Lighting"))
    }

    @Test
    fun curatedFastPatchesCaseInsensitive() {
        val upper = PatchFastMode.fastPatchNamesForTitle("bcus98174")
        val expected = setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro")
        assertEquals(expected, upper)
    }

    @Test
    fun curatedFastPatchesForUncharted2() {
        val patches = PatchFastMode.fastPatchNamesForTitle("BCUS98123")
        assertTrue(patches.contains("Disable in-built MLAA"))
        assertTrue(patches.contains("Disable Motion Blur"))
        assertTrue(patches.contains("Skip Intro"))
    }
}
