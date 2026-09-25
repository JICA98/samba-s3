package com.zenithblue.sambas3

import com.zenithblue.sambas3.patch.FastModeReceipt
import com.zenithblue.sambas3.patch.FastModeState
import com.zenithblue.sambas3.patch.FastPatchExperiment
import com.zenithblue.sambas3.patch.PatchFastMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PatchFastModeTest {

    @Before
    fun setUp() {
        PatchFastMode.enabledByTitle.clear()
        PatchRepository.patchProvider = null
        PatchRepository.patchEnabler = null
        PatchRepository.invalidate()
    }

    @After
    fun tearDown() {
        PatchFastMode.enabledByTitle.clear()
        PatchRepository.patchProvider = null
        PatchRepository.patchEnabler = null
        PatchRepository.invalidate()
    }

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
            val settings = PatchFastMode.fastModeSettingsForTitle(id)
            assertEquals("\"RPCS3 Scheduler\"", settings["Core@@Thread Scheduler Mode"])
            assertEquals("2", settings["Core@@Max LLVM Compile Threads"])
            assertEquals("\"Mega\"", settings["Core@@SPU Block Size"])
            assertTrue(!settings.containsKey("Video@@Driver Wake-Up Delay"))
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
            assertTrue(!settings.containsKey("Core@@Thread Scheduler Mode"))
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
        val result = PatchFastMode.setFastModeEnabled("UNKNOWN123", true)
        assertEquals(FastModeState.UNAVAILABLE, result.state)
        assertEquals(false, result.requested)
        val enabled = PatchFastMode.isFastModeEnabled("UNKNOWN123")
        assertEquals(false, enabled)
        assertEquals(false, PatchFastMode.isFastModeEnabledSync(null, "UNKNOWN123"))
        assertEquals(false, PatchFastMode.isFastModeRequestedSync(null, "UNKNOWN123"))
    }

    @Test
    fun requestedNotAppliedWhenZeroMatchingPatchesFound() = kotlinx.coroutines.runBlocking {
        // When title has curated patches listed but PatchRepository returns no matches
        PatchRepository.patchProvider = { emptyList() }
        PatchRepository.patchEnabler = { _, _, _, _ -> false }

        val receipt = PatchFastMode.setFastModeEnabled("BCUS98111", true)
        assertEquals(FastModeState.REQUESTED_NOT_APPLIED, receipt.state)
        assertTrue(receipt.requested)
        assertEquals(3, receipt.targetPatchCount)
        assertEquals(0, receipt.appliedPatches.size)
        assertEquals(3, receipt.missingPatches.size)
        assertTrue(receipt.missingPatches.contains("Disable MLAA"))
        assertTrue(receipt.missingPatches.contains("Disable Motion Blur"))
        assertTrue(receipt.missingPatches.contains("Skip intro"))
        assertTrue(receipt.effectiveSettings.isEmpty())
        assertTrue(receipt.message.contains("0/3"))

        // Must honestly reflect that patches were NOT applied:
        assertEquals(false, PatchFastMode.isFastModeEnabledSync(null, "BCUS98111"))
        // But user request state is preserved:
        assertTrue(PatchFastMode.isFastModeRequestedSync(null, "BCUS98111"))

        // Sync receipt query also reflects REQUESTED_NOT_APPLIED:
        val queryReceipt = PatchFastMode.getFastModeReceiptSync(null, "BCUS98111")
        assertEquals(FastModeState.REQUESTED_NOT_APPLIED, queryReceipt.state)
        assertTrue(queryReceipt.requested)
        assertEquals(3, queryReceipt.missingPatches.size)
    }

    @Test
    fun effectiveWhenAllCuratedPatchesApplied() = kotlinx.coroutines.runBlocking {
        val mockPatches = listOf(
            Patch(hash = "h1", name = "Disable MLAA", serials = listOf("BCUS98111"), enabledSerials = listOf("BCUS98111")),
            Patch(hash = "h2", name = "Disable Motion Blur", serials = listOf("BCUS98111"), enabledSerials = listOf("BCUS98111")),
            Patch(hash = "h3", name = "Skip intro", serials = listOf("BCUS98111"), enabledSerials = listOf("BCUS98111"))
        )
        PatchRepository.patchProvider = { mockPatches }
        PatchRepository.patchEnabler = { _, _, _, _ -> true }

        val receipt = PatchFastMode.setFastModeEnabled("BCUS98111", true)
        assertEquals(FastModeState.EFFECTIVE, receipt.state)
        assertTrue(receipt.requested)
        assertEquals(3, receipt.targetPatchCount)
        assertEquals(3, receipt.appliedPatches.size)
        assertTrue(receipt.missingPatches.isEmpty())
        assertEquals("\"RPCS3 Scheduler\"", receipt.effectiveSettings["Core@@Thread Scheduler Mode"])
        assertEquals("2", receipt.effectiveSettings["Core@@Max LLVM Compile Threads"])
        assertEquals("\"Mega\"", receipt.effectiveSettings["Core@@SPU Block Size"])

        assertTrue(PatchFastMode.isFastModeEnabledSync(null, "BCUS98111"))
        assertTrue(PatchFastMode.isFastModeRequestedSync(null, "BCUS98111"))
    }

    @Test
    fun partialWhenSubsetOfCuratedPatchesApplied() = kotlinx.coroutines.runBlocking {
        val mockPatches = listOf(
            Patch(hash = "h1", name = "Disable MLAA", serials = listOf("BCUS98111"), enabledSerials = listOf("BCUS98111"))
        )
        PatchRepository.patchProvider = { mockPatches }
        PatchRepository.patchEnabler = { _, name, _, _ -> name == "Disable MLAA" }

        val receipt = PatchFastMode.setFastModeEnabled("BCUS98111", true)
        assertEquals(FastModeState.PARTIAL, receipt.state)
        assertTrue(receipt.requested)
        assertEquals(3, receipt.targetPatchCount)
        assertEquals(1, receipt.appliedPatches.size)
        assertEquals(listOf("Disable MLAA"), receipt.appliedPatches)
        assertEquals(2, receipt.missingPatches.size)
        assertTrue(receipt.missingPatches.contains("Disable Motion Blur"))
        assertTrue(receipt.missingPatches.contains("Skip intro"))
        // Partial state still allows effective settings
        assertEquals("\"Mega\"", receipt.effectiveSettings["Core@@SPU Block Size"])

        assertTrue(PatchFastMode.isFastModeEnabledSync(null, "BCUS98111"))
    }

    @Test
    fun disabledStateWhenUserTurnsFastModeOff() = kotlinx.coroutines.runBlocking {
        val receipt = PatchFastMode.setFastModeEnabled("BCUS98111", false)
        assertEquals(FastModeState.DISABLED, receipt.state)
        assertEquals(false, receipt.requested)
        assertEquals(3, receipt.targetPatchCount)
        assertTrue(receipt.appliedPatches.isEmpty())
        assertTrue(receipt.effectiveSettings.isEmpty())
        assertEquals(false, PatchFastMode.isFastModeEnabledSync(null, "BCUS98111"))
        assertEquals(false, PatchFastMode.isFastModeRequestedSync(null, "BCUS98111"))
    }

    @Test
    fun gowProfileSettingsGenerationAndTitleSwitchingIsolation() {
        val gowSettings = PatchFastMode.fastModeSettingsForTitle("BCUS98111")
        assertEquals("\"RPCS3 Scheduler\"", gowSettings["Core@@Thread Scheduler Mode"])
        assertEquals("2", gowSettings["Core@@Max LLVM Compile Threads"])
        assertEquals("\"Mega\"", gowSettings["Core@@SPU Block Size"])
        assertTrue(!gowSettings.containsKey("Video@@Driver Wake-Up Delay"))

        val gtaSettings = PatchFastMode.fastModeSettingsForTitle("BLJM61019")
        assertEquals("1", gtaSettings["Video@@Driver Wake-Up Delay"])
        assertTrue(!gtaSettings.containsKey("Core@@Thread Scheduler Mode"))
        assertTrue(!gtaSettings.containsKey("Core@@Max LLVM Compile Threads"))
        assertTrue(!gtaSettings.containsKey("Core@@SPU Block Size"))

        val tlouSettings = PatchFastMode.fastModeSettingsForTitle("BCUS98174")
        assertTrue(tlouSettings.isEmpty())

        val unsupported = PatchFastMode.fastModeSettingsForTitle("UNKNOWN123")
        assertTrue(unsupported.isEmpty())
    }

    @Test
    fun fastPatchExperimentsE05AndE06Granularity() = kotlinx.coroutines.runBlocking {
        val titleId = "BCUS98111"

        // E05: MLAA removal alone
        val mlaaOnly = PatchFastMode.fastPatchNamesForTitle(titleId, FastPatchExperiment.MLAA_ONLY)
        assertEquals(setOf("Disable MLAA"), mlaaOnly)

        // E06 single: Motion blur removal alone
        val mbOnly = PatchFastMode.fastPatchNamesForTitle(titleId, FastPatchExperiment.MOTION_BLUR_ONLY)
        assertEquals(setOf("Disable Motion Blur"), mbOnly)

        // E06 combined: MLAA + Motion Blur (no intro skip)
        val combined = PatchFastMode.fastPatchNamesForTitle(titleId, FastPatchExperiment.COMBINED_NO_INTRO)
        assertEquals(setOf("Disable MLAA", "Disable Motion Blur"), combined)

        // All: MLAA + Motion Blur + Skip intro
        val all = PatchFastMode.fastPatchNamesForTitle(titleId, FastPatchExperiment.ALL)
        assertEquals(setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"), all)

        // Granular flags
        val custom = PatchFastMode.fastPatchNamesForTitle(
            titleId,
            enableMlaa = true,
            enableMotionBlur = false,
            enableSkipIntro = false
        )
        assertEquals(setOf("Disable MLAA"), custom)

        // Applying E05 experiment via mock
        val mockPatches = listOf(
            Patch(hash = "h1", name = "Disable MLAA", serials = listOf("BCUS98111")),
            Patch(hash = "h2", name = "Disable Motion Blur", serials = listOf("BCUS98111")),
            Patch(hash = "h3", name = "Skip intro", serials = listOf("BCUS98111"))
        )
        PatchRepository.patchProvider = { mockPatches }
        PatchRepository.patchEnabler = { _, _, _, _ -> true }

        val e05Receipt = PatchFastMode.applyFastModeExperiment(titleId, FastPatchExperiment.MLAA_ONLY)
        assertEquals(FastModeState.EFFECTIVE, e05Receipt.state)
        assertEquals(1, e05Receipt.targetPatchCount)
        assertEquals(listOf("Disable MLAA"), e05Receipt.appliedPatches)

        // Granular setter
        val granularReceipt = PatchFastMode.setFastModeGranular(
            titleId,
            enableMlaa = false,
            enableMotionBlur = true,
            enableSkipIntro = false
        )
        assertEquals(FastModeState.EFFECTIVE, granularReceipt.state)
        assertEquals(1, granularReceipt.targetPatchCount)
        assertEquals(listOf("Disable Motion Blur"), granularReceipt.appliedPatches)
    }

    @Test
    fun tlouFastModeHasNoGtaSettings() {
        val settings = PatchFastMode.fastModeSettingsForTitle("BCUS98174")
        assertTrue("TLOU Fast Mode must not inherit GTA V engine settings", settings.isEmpty())
    }
}
