package com.zenithblue.sambas3.utils

import com.zenithblue.sambas3.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playstore-only source set: external GPU driver acquisition must be compiled out of product policy.
 */
class PlaystoreExternalInstallDisabledTest {

    @Test
    fun playstore_disallows_external_gpu_drivers() {
        assertTrue(BuildConfig.IS_PLAYSTORE_BUILD)
        assertFalse(BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS)
        assertTrue(BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS)
    }
}
