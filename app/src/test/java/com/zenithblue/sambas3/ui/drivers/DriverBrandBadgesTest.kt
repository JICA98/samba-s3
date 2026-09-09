package com.zenithblue.sambas3.ui.drivers

import com.zenithblue.sambas3.R
import com.zenithblue.sambas3.utils.AdrenoGpuInfo
import com.zenithblue.sambas3.utils.GpuDriverHelper
import com.zenithblue.sambas3.utils.GpuFamily
import com.zenithblue.sambas3.utils.GpuDriverMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverBrandBadgesTest {

    private val adreno = AdrenoGpuInfo("740", GpuFamily.ADRENO_7XX, "Adreno (TM) 740", true, true)
    private val mali = AdrenoGpuInfo(null, GpuFamily.UNKNOWN, "Mali-G78", false, true)
    private val system = GpuDriverHelper.getSystemDriverMetadata()
    private val turnip = GpuDriverMetadata(
        name = "Turnip 26.3", author = "SambaS3", packageVersion = "1", vendor = "Mesa",
        driverVersion = "26.3", minApi = 28, description = "turnip",
        libraryName = "libvulkan_freedreno.so", bundledId = "turnip-26.3",
        isBundled = true, displayName = "Turnip 26.3 — Latest",
    )

    @Test
    fun system_chip_uses_vendor() {
        assertEquals("QUALCOMM ADRENO", systemVendorChip(adreno))
        assertEquals("ARM MALI", systemVendorChip(mali))
    }

    @Test
    fun mali_detected() {
        assertTrue(isMaliDevice(mali))
        assertFalse(isMaliDevice(adreno))
    }

    @Test
    fun turnip_gets_mesa_vulkan_chips() {
        assertEquals(listOf("MESA", "VULKAN"), brandChipsFor(turnip, "QUALCOMM ADRENO"))
    }

    @Test
    fun system_gets_vendor_vulkan_chips() {
        assertEquals(listOf("ARM MALI", "VULKAN"), brandChipsFor(system, "ARM MALI"))
        assertEquals(listOf("QUALCOMM ADRENO", "VULKAN"), brandChipsFor(system, "QUALCOMM ADRENO"))
    }

    @Test
    fun coming_soon_lists_vortex_and_wrapper() {
        val titles = comingSoonDrivers.map { it.title }
        assertTrue(titles.contains("Vortex"))
        assertTrue(titles.contains("Wrapper Driver"))
        assertEquals(2, comingSoonDrivers.size)
    }

    @Test
    fun brand_logos_map_to_real_assets() {
        val turnipLogos = brandLogosFor(turnip, "QUALCOMM ADRENO", R.drawable.hw_gpu_adreno)
        assertEquals("MESA", turnipLogos[0].label)
        assertEquals(R.drawable.hw_driver_mesa, turnipLogos[0].iconRes)
        assertEquals("VULKAN", turnipLogos[1].label)
        assertEquals(R.drawable.hw_driver_vulkan, turnipLogos[1].iconRes)

        val systemLogos = brandLogosFor(system, "ARM MALI", R.drawable.hw_gpu_mali)
        assertEquals("ARM MALI", systemLogos[0].label)
        assertEquals(R.drawable.hw_gpu_mali, systemLogos[0].iconRes)
        assertEquals(R.drawable.hw_driver_vulkan, systemLogos[1].iconRes)

        assertEquals(R.drawable.hw_gpu_adreno, vendorLogoRes(adreno))
        assertEquals(R.drawable.hw_gpu_mali, vendorLogoRes(mali))
        assertEquals(R.drawable.ic_brand_vortex, comingSoonDrivers[0].logoRes)
        assertEquals(R.drawable.ic_brand_wrapper, comingSoonDrivers[1].logoRes)
    }
}
