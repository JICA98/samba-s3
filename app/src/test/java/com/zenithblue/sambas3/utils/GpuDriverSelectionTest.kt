package com.zenithblue.sambas3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class GpuDriverSelectionTest {

    private val adreno750 = AdrenoGpuInfo(
        gpuId = "750",
        family = GpuFamily.ADRENO_7XX,
        rawModel = "Adreno (TM) 750",
        isAdreno = true,
        isArm64 = true,
    )
    private val mali = AdrenoGpuInfo(
        gpuId = null,
        family = GpuFamily.UNKNOWN,
        rawModel = "Mali-G615 MC6",
        isAdreno = false,
        isArm64 = true,
    )
    private val system = GpuDriverSelection.CurrentDriverSelection("Default", "")
    private val custom = GpuDriverSelection.CurrentDriverSelection("turnip-26.3", "/data/user/0/x/files/gpu_drivers/turnip-26.3")

    private val turnipRecommended = BundledGpuDriverEntry(
        id = "turnip-26.3",
        displayName = "Turnip 26.3 - Recommended",
        role = "recommended",
        packageFile = "turnip-26.3.zip",
        supportedGpuFamilies = listOf("adreno7xx"),
        sha256 = "aa",
    )
    private val turnipCompat = BundledGpuDriverEntry(
        id = "turnip-25.3-compat",
        displayName = "Turnip 25.3 - Compatibility",
        role = "compatibility",
        packageFile = "turnip-25.3.zip",
        supportedGpuFamilies = listOf("adreno7xx"),
        sha256 = "bb",
    )
    private val turnipExp = BundledGpuDriverEntry(
        id = "turnip-a8xx-exp",
        displayName = "Turnip A8XX - Experimental",
        role = "experimental",
        packageFile = "turnip-a8xx.zip",
        supportedGpuIds = listOf("750"),
        experimental = true,
        sha256 = "cc",
    )

    @Test
    fun rdr_adreno_system_with_turnip_resolves_override() {
        val spec = GpuDriverSelection.resolveCompatBootDriver(
            "BLUS30758", adreno750, system,
            listOf(turnipRecommended), setOf("turnip-26.3")
        )
        assertNotNull(spec)
        assertEquals("turnip-26.3", spec!!.entryId)
    }

    @Test
    fun rdr_adreno_explicit_custom_driver_is_respected() {
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30758", adreno750, custom,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
    }

    @Test
    fun rdr_mali_never_overridden() {
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30758", mali, system,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
    }

    @Test
    fun non_rdr_adreno_untouched() {
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30443", adreno750, system,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30441", adreno750, system,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                null, adreno750, system,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
    }

    @Test
    fun rdr_turnip_missing_falls_back_to_system() {
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30758", adreno750, system,
                listOf(turnipRecommended), emptySet()
            )
        )
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30758", adreno750, system,
                emptyList(), emptySet()
            )
        )
    }

    @Test
    fun recommended_beats_compatibility_and_experimental_never_auto_selected() {
        // Recommended wins over compatibility role.
        val picked = GpuDriverSelection.chooseCompatEntry(
            listOf(turnipCompat, turnipRecommended),
            setOf("turnip-26.3", "turnip-25.3-compat")
        )
        assertEquals("turnip-26.3", picked!!.id)
        // Experimental-only install is never auto-selected.
        assertNull(
            GpuDriverSelection.chooseCompatEntry(listOf(turnipExp), setOf("turnip-a8xx-exp"))
        )
        assertNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "BLUS30758", adreno750, system,
                listOf(turnipExp), setOf("turnip-a8xx-exp")
            )
        )
    }

    @Test
    fun title_match_is_case_insensitive_and_validated_title_known() {
        assertNotNull(
            GpuDriverSelection.resolveCompatBootDriver(
                "blus30758", adreno750, system,
                listOf(turnipRecommended), setOf("turnip-26.3")
            )
        )
        assertEquals("BLUS30758", GpuDriverSelection.RDR_VALIDATED_TITLE)
    }
}
