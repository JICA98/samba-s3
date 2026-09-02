package com.zenithblue.sambas3

/**
 * Pure watchdog decision for stuck runtime PPU UI at 100%/modules-done without a terminal.
 * May clear transient UI state only — never establishes validated Runtime readiness.
 */
data class WatchdogClearDecision(
    val shouldClearUiActive: Boolean,
    val establishesValidatedReady: Boolean = false,
    val missingTerminal: Boolean = false,
    val logMessage: String? = null,
)

object CompileWatchdogLogic {

    fun evaluateStuckAtComplete(
        ppuActive: Boolean,
        ppuPercent: Int,
        moduleDone: Int,
        moduleTotal: Int,
        jobMatches: Boolean,
    ): WatchdogClearDecision {
        val stuck = jobMatches &&
            ppuActive &&
            ppuPercent == 100 &&
            moduleTotal > 0 &&
            moduleDone == moduleTotal
        if (!stuck) {
            return WatchdogClearDecision(shouldClearUiActive = false)
        }
        return WatchdogClearDecision(
            shouldClearUiActive = true,
            establishesValidatedReady = false,
            missingTerminal = true,
            logMessage = "PPU watchdog: stuck at 100% $moduleDone/$moduleTotal without terminal — clearing UI only, not validating readiness",
        )
    }
}
