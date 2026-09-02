package com.zenithblue.sambas3

/**
 * Typed terminal outcome for a compile job. Survives active→false so consumers
 * never treat mere inactivity as success.
 */
enum class CompileOutcome {
    NONE,
    COMPLETED,
    FAILED,
    CANCELED,
    /** Intermediate multistep boundary — more work remains; never PreRuntime READY. */
    STEP_MORE,
}

/**
 * Pure decision for whether an INSTALL PPU terminal may mark PreRuntime READY.
 * Only matching INSTALL + title + job + COMPLETED qualifies.
 */
object InstallPpuTerminalLogic {
    data class Decision(
        val markPreRuntimeReady: Boolean,
        val reason: String,
    )

    fun decide(
        installPpuWasSeen: Boolean,
        ppuActive: Boolean,
        outcome: CompileOutcome,
        terminalTitleId: String?,
        terminalJobId: Long,
        expectedTitleId: String?,
        expectedJobId: Long?,
    ): Decision {
        if (ppuActive) {
            return Decision(false, "still_active")
        }
        if (!installPpuWasSeen) {
            return Decision(false, "install_ppu_not_seen")
        }
        if (outcome == CompileOutcome.NONE) {
            return Decision(false, "outcome_none")
        }
        if (outcome == CompileOutcome.STEP_MORE) {
            return Decision(false, "step_more")
        }
        if (outcome == CompileOutcome.FAILED) {
            return Decision(false, "failed")
        }
        if (outcome == CompileOutcome.CANCELED) {
            return Decision(false, "canceled")
        }
        if (outcome != CompileOutcome.COMPLETED) {
            return Decision(false, "unknown_outcome")
        }
        val title = terminalTitleId?.trim().orEmpty()
        if (title.isEmpty()) {
            return Decision(false, "missing_title")
        }
        val expected = expectedTitleId?.trim().orEmpty()
        if (expected.isNotEmpty() && !expected.equals(title, ignoreCase = true)) {
            return Decision(false, "wrong_title")
        }
        if (expectedJobId != null && expectedJobId > 0L && terminalJobId != expectedJobId) {
            return Decision(false, "wrong_job")
        }
        if (terminalJobId <= 0L) {
            return Decision(false, "missing_job")
        }
        return Decision(true, "completed")
    }
}
