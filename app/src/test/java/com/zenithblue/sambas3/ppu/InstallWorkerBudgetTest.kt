package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallWorkerBudgetTest {

    @Test
    fun install_configured0_host8_cap2_yields2() {
        assertEquals(
            2,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 0,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = true,
            ),
        )
    }

    @Test
    fun install_configured1_host8_cap2_respectsUserLowerLimit() {
        assertEquals(
            1,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 1,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = true,
            ),
        )
    }

    @Test
    fun install_configured4_host8_cap2_capsAt2() {
        assertEquals(
            2,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 4,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = true,
            ),
        )
    }

    @Test
    fun install_configured16_host8_cap2_capsAt2() {
        assertEquals(
            2,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 16,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = true,
            ),
        )
    }

    @Test
    fun runtime_configured0_host8_uncappedByInstallCap() {
        assertEquals(
            8,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 0,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = false,
            ),
        )
    }

    @Test
    fun runtime_configured4_host8_usesConfigured() {
        assertEquals(
            4,
            InstallWorkerBudget.effectiveWorkers(
                configuredLlvmThreads = 4,
                hostThreads = 8,
                installSafeCap = 2,
                isInstallOrigin = false,
            ),
        )
    }
}
