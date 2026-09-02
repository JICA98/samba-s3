package com.zenithblue.sambas3.ppu

/**
 * Pure helper for INSTALL-origin PPU worker caps.
 *
 * Runtime PPU uses the user's configured effective workers unchanged.
 * INSTALL may apply an internal safe cap that never raises a user-requested
 * lower limit (configured 0 means "all host threads" before the install cap).
 */
object InstallWorkerBudget {
    const val DEFAULT_ANDROID_INSTALL_SAFE_CAP = 2

    /**
     * @param configuredLlvmThreads user/global `Max LLVM Compile Threads` (0 = all host)
     * @param hostThreads hardware thread count
     * @param installSafeCap internal INSTALL-only ceiling (default 2)
     * @param isInstallOrigin when false, installSafeCap is ignored
     */
    fun effectiveWorkers(
        configuredLlvmThreads: Int,
        hostThreads: Int,
        installSafeCap: Int = DEFAULT_ANDROID_INSTALL_SAFE_CAP,
        isInstallOrigin: Boolean,
    ): Int {
        val host = hostThreads.coerceAtLeast(1)
        val configuredEffective = if (configuredLlvmThreads > 0) {
            minOf(configuredLlvmThreads, host)
        } else {
            host
        }
        if (!isInstallOrigin) return configuredEffective
        val cap = installSafeCap.coerceAtLeast(1)
        return minOf(configuredEffective, cap)
    }
}
