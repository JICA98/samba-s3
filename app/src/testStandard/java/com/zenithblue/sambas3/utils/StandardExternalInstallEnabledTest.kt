package com.zenithblue.sambas3.utils

import com.zenithblue.sambas3.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Standard-only source set: external GPU driver import/download remains available.
 */
class StandardExternalInstallEnabledTest {

    @Test
    fun standard_allows_external_gpu_drivers() {
        assertFalse(BuildConfig.IS_PLAYSTORE_BUILD)
        assertTrue(BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS)
        assertFalse(BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS)
    }

    @Test
    fun github_driver_channel_constant_present() {
        // Class only exists on standard flavor
        assertTrue(DefaultGpuDriverChannel.contains("K11MCH1") || DefaultGpuDriverChannel.contains("github.com"))
    }
}
