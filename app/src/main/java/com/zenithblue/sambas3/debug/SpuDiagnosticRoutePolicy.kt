package com.zenithblue.sambas3.debug

/** Selects the no-video lifecycle used only by explicitly validated SPU fixtures. */
internal object SpuDiagnosticRoutePolicy {
    const val WAIT_TIMEOUT_MS = 90_000L

    fun usesFrameReadiness(isFixture: Boolean): Boolean = !isFixture

    fun usesNoFrameWatchdog(isFixture: Boolean): Boolean = !isFixture

    fun timedOut(isFixture: Boolean, elapsedMs: Long): Boolean =
        isFixture && elapsedMs >= WAIT_TIMEOUT_MS
}
