package com.zenithblue.sambas3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdrenoGpuDetectorTest {

    private val turnip26 = BundledGpuDriverEntry(
        id = "turnip-26.1.4",
        displayName = "Turnip 26.1.4 — Recommended",
        role = "recommended",
        packageFile = "turnip-26.1.4-sambas3.zip",
        supportedGpuFamilies = listOf("adreno6xx", "adreno7xx"),
        sha256 = "aa",
    )

    private val a8xx = BundledGpuDriverEntry(
        id = "turnip-a8xx-v29",
        displayName = "Turnip A8XX v29 — Experimental",
        role = "experimental",
        packageFile = "turnip-a8xx-v29-sambas3.zip",
        supportedGpuIds = listOf("810", "825", "829", "830", "840"),
        forceSysmemGpuIds = listOf("830"),
        experimental = true,
        sha256 = "cc",
    )

    @Test
    fun extract_gpu_id_from_common_strings() {
        assertEquals("740", AdrenoGpuDetector.extractGpuId("Adreno (TM) 740"))
        assertEquals("830", AdrenoGpuDetector.extractGpuId("Adreno830"))
        assertEquals("640", AdrenoGpuDetector.extractGpuId("adreno 640"))
    }

    @Test
    fun family_from_gpu_id() {
        assertEquals(GpuFamily.ADRENO_6XX, AdrenoGpuDetector.familyFromGpuId("640"))
        assertEquals(GpuFamily.ADRENO_7XX, AdrenoGpuDetector.familyFromGpuId("750"))
        assertEquals(GpuFamily.ADRENO_8XX, AdrenoGpuDetector.familyFromGpuId("830"))
    }

    @Test
    fun adreno7xx_sees_turnip26_not_a8xx() {
        val info = AdrenoGpuInfo(
            gpuId = "740",
            family = GpuFamily.ADRENO_7XX,
            rawModel = "Adreno (TM) 740",
            isAdreno = true,
            isArm64 = true,
        )
        assertTrue(AdrenoGpuDetector.isCompatible(turnip26, info))
        assertFalse(AdrenoGpuDetector.isCompatible(a8xx, info))
    }

    @Test
    fun adreno830_sees_a8xx_not_turnip26() {
        val info = AdrenoGpuInfo(
            gpuId = "830",
            family = GpuFamily.ADRENO_8XX,
            rawModel = "Adreno (TM) 830",
            isAdreno = true,
            isArm64 = true,
        )
        assertFalse(AdrenoGpuDetector.isCompatible(turnip26, info))
        assertTrue(AdrenoGpuDetector.isCompatible(a8xx, info))
        assertTrue(AdrenoGpuDetector.shouldForceSysmem(a8xx, info))
    }

    @Test
    fun x86_64_not_compatible() {
        val info = AdrenoGpuInfo(
            gpuId = "740",
            family = GpuFamily.ADRENO_7XX,
            rawModel = "Adreno (TM) 740",
            isAdreno = true,
            isArm64 = false,
        )
        assertFalse(AdrenoGpuDetector.isCompatible(turnip26, info))
        assertFalse(AdrenoGpuDetector.isCompatible(a8xx, info))
    }

    @Test
    fun non_adreno_not_compatible() {
        val info = AdrenoGpuInfo(
            gpuId = null,
            family = GpuFamily.UNKNOWN,
            rawModel = "Mali-G78",
            isAdreno = false,
            isArm64 = true,
        )
        assertFalse(AdrenoGpuDetector.isCompatible(turnip26, info))
    }

    @Test
    fun a8xx_never_auto_selected_by_role() {
        // Selection policy: experimental role must never be default
        assertTrue(a8xx.experimental)
        assertFalse(a8xx.isRecommended)
    }
}
