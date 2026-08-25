package com.zenithblue.sambas3.utils

import com.zenithblue.sambas3.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flavor capability and policy tests. Values depend on the Gradle test variant.
 */
class GpuDriverPolicyTest {

    @Test
    fun buildconfig_flags_are_consistent() {
        // Both flavors now bundle Turnip 26.3 (shared assets), but only standard allows external acquisition.
        if (BuildConfig.IS_PLAYSTORE_BUILD) {
            assertTrue(BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS)
            assertFalse(BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS)
        } else {
            assertTrue(BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS)
            assertTrue(BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS)
        }
    }

    @Test
    fun system_driver_default_label() {
        val system = GpuDriverHelper.getSystemDriverMetadata()
        assertEquals("Default", system.name)
        assertEquals("Default", system.label)
        assertFalse(system.isBundled)
        assertFalse(system.experimental)
    }

    @Test
    fun a8xx_not_default_selection_candidate() {
        // Default selection is always system ("Default"), never experimental A8XX.
        val system = GpuDriverHelper.getSystemDriverMetadata()
        assertEquals("Default", system.label)
        assertFalse(system.bundledId == "turnip-a8xx-v29")
    }
}
