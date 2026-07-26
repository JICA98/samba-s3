package com.zenithblue.sambas3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledDriverVisibilityTest {

    private val systemMeta = GpuDriverHelper.getSystemDriverMetadata()
    private val systemFile = File("/system/vendor")

    private val turnip26 = GpuDriverMetadata(
        name = "Turnip 26.1.4",
        author = "SambaS3",
        packageVersion = "1",
        vendor = "Mesa",
        driverVersion = "26.1.4",
        minApi = 28,
        description = "rec",
        libraryName = "libvulkan_freedreno.so",
        bundledId = "turnip-26.1.4",
        isBundled = true,
        role = "recommended",
        displayName = "Turnip 26.1.4 — Recommended",
    )

    private val a8xx = GpuDriverMetadata(
        name = "Turnip A8XX v29",
        author = "SambaS3",
        packageVersion = "1",
        vendor = "Mesa",
        driverVersion = "A8XX v29",
        minApi = 28,
        description = "exp",
        libraryName = "libvulkan_freedreno.so",
        bundledId = "turnip-a8xx-v29",
        isBundled = true,
        experimental = true,
        role = "experimental",
        displayName = "Turnip A8XX v29 — Experimental",
    )

    private val catalog = listOf(
        BundledGpuDriverEntry(
            id = "turnip-26.1.4",
            displayName = "Turnip 26.1.4 — Recommended",
            role = "recommended",
            packageFile = "turnip-26.1.4-sambas3.zip",
            supportedGpuFamilies = listOf("adreno6xx", "adreno7xx"),
            sha256 = "aa",
        ),
        BundledGpuDriverEntry(
            id = "turnip-a8xx-v29",
            displayName = "Turnip A8XX v29 — Experimental",
            role = "experimental",
            packageFile = "turnip-a8xx-v29-sambas3.zip",
            supportedGpuIds = listOf("810", "825", "829", "830", "840"),
            experimental = true,
            sha256 = "cc",
        ),
    )

    private val installed = mapOf(
        systemFile to systemMeta,
        File("/data/data/app/files/gpu_drivers/turnip-26.1.4") to turnip26,
        File("/data/data/app/files/gpu_drivers/turnip-a8xx-v29") to a8xx,
    )

    @Test
    fun adreno7xx_shows_system_and_turnip26_not_a8xx() {
        val info = AdrenoGpuInfo("740", GpuFamily.ADRENO_7XX, "Adreno740", true, true)
        val filtered = BundledDriverVisibility.filterForDevice(installed, info, catalog, true)
        val ids = filtered.values.map { it.bundledId ?: it.name }.toSet()
        assertTrue(ids.contains("Default"))
        assertTrue(ids.contains("turnip-26.1.4"))
        assertEquals(false, ids.contains("turnip-a8xx-v29"))
    }

    @Test
    fun adreno830_shows_system_and_a8xx_not_turnip26() {
        val info = AdrenoGpuInfo("830", GpuFamily.ADRENO_8XX, "Adreno830", true, true)
        val filtered = BundledDriverVisibility.filterForDevice(installed, info, catalog, true)
        val ids = filtered.values.map { it.bundledId ?: it.name }.toSet()
        assertTrue(ids.contains("Default"))
        assertTrue(ids.contains("turnip-a8xx-v29"))
        assertEquals(false, ids.contains("turnip-26.1.4"))
    }

    @Test
    fun unsupported_or_x86_only_system() {
        val x86 = AdrenoGpuInfo("740", GpuFamily.ADRENO_7XX, "Adreno740", true, false)
        val filtered = BundledDriverVisibility.filterForDevice(installed, x86, catalog, true)
        assertEquals(1, filtered.size)
        assertEquals("Default", filtered.values.first().name)
    }

    @Test
    fun no_custom_loading_only_system() {
        val info = AdrenoGpuInfo("740", GpuFamily.ADRENO_7XX, "Adreno740", true, true)
        val filtered = BundledDriverVisibility.filterForDevice(installed, info, catalog, false)
        assertEquals(1, filtered.size)
    }
}
