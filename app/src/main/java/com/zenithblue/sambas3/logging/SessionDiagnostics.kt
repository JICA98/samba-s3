package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.crash.CrashClassification

enum class SessionOutcome {
    CLEAN_STOP,
    CONFIRMED_CRASH,
    FAILED,
    BOOT_FAILURE,
    INTERRUPTED,
    RUNNING,
    UNKNOWN,
}

enum class CaptureState {
    RECORDING,
    FINALIZING,
    SEALED,
    RECOVERED_PARTIAL,
    FAILED,
}

enum class DiagnosticCause {
    FRAME_TIMEOUT,
    NATIVE_CRASH,
    GPU_RENDERER,
    BOOT_LOAD_FAILURE,
    OS_KILL,
    UNPROVEN_STOP,
}

data class SourceHealth(
    val kind: LogSourceKind,
    val state: String,
    val producer: String? = null,
    val bytes: Long = 0L,
    val error: String? = null,
)

data class SessionDiagnosticsView(
    val sessionId: String,
    val revision: Long,
    val outcome: SessionOutcome,
    val rawStopReason: String?,
    val diagnosticCause: DiagnosticCause?,
    val captureState: CaptureState,
    val classification: CrashClassification,
    val artifacts: List<LogArtifact>,
    val sources: List<SourceHealth> = emptyList(),
    val captureError: String? = null,
    val title: String = "",
    val titleId: String? = null,
)

object SessionDiagnostics {
    private val CLEAN_FRONTEND_REASONS = setOf(
        "InGameExit",
        "HomeStop",
        "AppRecoveryCleanup",
        "user-exit",
        "clean",
    )

    private val BOOT_REASONS = setOf(
        "BootFailureCleanup",
        "LoadFailureCleanup",
        "LoadFailure",
        "BootFailure",
    )

    fun isCleanFrontendReason(reason: String?): Boolean =
        reason != null && reason in CLEAN_FRONTEND_REASONS

    fun isBootFailureReason(reason: String?): Boolean =
        reason != null && (reason in BOOT_REASONS || reason.contains("BootFailure", true) || reason.contains("LoadFailure", true))

    fun project(
        manifest: LogSessionManifest,
        fatalEventId: String? = null,
        osExitEvidence: Boolean = false,
        evidenceHint: String = "",
    ): SessionDiagnosticsView {
        val typedFatal = !fatalEventId.isNullOrBlank() ||
            manifest.terminalState == LogSessionTerminal.CRASHED
        val gpuEvidence = containsGpuFatal(evidenceHint)
        val nativeEvidence = containsNativeFatal(evidenceHint)
        val frameTimeoutEvidence = containsFrameTimeout(evidenceHint)
        val capture = manifest.captureState
        val outcome = when {
            typedFatal && (nativeEvidence || fatalEventId != null || manifest.terminalState == LogSessionTerminal.CRASHED) ->
                SessionOutcome.CONFIRMED_CRASH
            typedFatal -> SessionOutcome.CONFIRMED_CRASH
            osExitEvidence && manifest.terminalState != LogSessionTerminal.CLEAN_STOP ->
                SessionOutcome.CONFIRMED_CRASH
            manifest.terminalState == LogSessionTerminal.FAILED && isBootFailureReason(manifest.stopReason) ->
                SessionOutcome.BOOT_FAILURE
            manifest.terminalState == LogSessionTerminal.FAILED -> SessionOutcome.FAILED
            manifest.terminalState == LogSessionTerminal.INTERRUPTED -> SessionOutcome.INTERRUPTED
            manifest.terminalState == LogSessionTerminal.CLEAN_STOP -> SessionOutcome.CLEAN_STOP
            manifest.terminalState == LogSessionTerminal.RUNNING -> SessionOutcome.RUNNING
            else -> SessionOutcome.UNKNOWN
        }
        val cause = when {
            outcome == SessionOutcome.CLEAN_STOP -> null
            frameTimeoutEvidence -> DiagnosticCause.FRAME_TIMEOUT
            gpuEvidence -> DiagnosticCause.GPU_RENDERER
            outcome == SessionOutcome.CONFIRMED_CRASH && nativeEvidence -> DiagnosticCause.NATIVE_CRASH
            outcome == SessionOutcome.CONFIRMED_CRASH && osExitEvidence -> DiagnosticCause.OS_KILL
            outcome == SessionOutcome.BOOT_FAILURE -> DiagnosticCause.BOOT_LOAD_FAILURE
            outcome == SessionOutcome.INTERRUPTED || outcome == SessionOutcome.UNKNOWN -> DiagnosticCause.UNPROVEN_STOP
            outcome == SessionOutcome.FAILED -> DiagnosticCause.UNPROVEN_STOP
            outcome == SessionOutcome.CONFIRMED_CRASH -> DiagnosticCause.NATIVE_CRASH
            else -> null
        }
        return SessionDiagnosticsView(
            sessionId = manifest.sessionId,
            revision = manifest.revision,
            outcome = outcome,
            rawStopReason = manifest.stopReason,
            diagnosticCause = cause,
            captureState = capture,
            classification = classificationFor(outcome),
            artifacts = manifest.artifacts,
            captureError = manifest.captureError,
            title = manifest.gameTitleSnapshot,
            titleId = manifest.titleId,
        )
    }

    fun classificationFor(outcome: SessionOutcome): CrashClassification = when (outcome) {
        SessionOutcome.CLEAN_STOP -> CrashClassification.CLEAN_STOP
        SessionOutcome.CONFIRMED_CRASH -> CrashClassification.CONFIRMED_CRASH
        SessionOutcome.FAILED, SessionOutcome.BOOT_FAILURE -> CrashClassification.RECOVERABLE_EMULATOR_FAILURE
        SessionOutcome.INTERRUPTED, SessionOutcome.UNKNOWN -> CrashClassification.UNEXPECTED_TERMINATION
        SessionOutcome.RUNNING -> CrashClassification.UNEXPECTED_TERMINATION
    }

    fun isCrashSession(manifest: LogSessionManifest, fatalEventId: String? = null): Boolean {
        val view = project(manifest, fatalEventId)
        return isCrashSession(view)
    }

    fun isCrashSession(view: SessionDiagnosticsView): Boolean = when (view.outcome) {
        SessionOutcome.CONFIRMED_CRASH,
        SessionOutcome.FAILED,
        SessionOutcome.BOOT_FAILURE,
        SessionOutcome.INTERRUPTED,
        -> true
        SessionOutcome.CLEAN_STOP, SessionOutcome.RUNNING -> false
        SessionOutcome.UNKNOWN -> true
    }

    fun preferTerminal(current: LogSessionTerminal, incoming: LogSessionTerminal): LogSessionTerminal {
        if (rank(incoming) < rank(current) && current != LogSessionTerminal.RUNNING) return current
        return incoming
    }

    private fun rank(state: LogSessionTerminal): Int = when (state) {
        LogSessionTerminal.CRASHED -> 5
        LogSessionTerminal.FAILED -> 4
        LogSessionTerminal.INTERRUPTED -> 3
        LogSessionTerminal.CLEAN_STOP -> 2
        LogSessionTerminal.RUNNING -> 1
    }

    private fun containsGpuFatal(evidence: String): Boolean =
        Regex("VK_ERROR_DEVICE_LOST|native renderer fatal|gpu fault|driver crash", RegexOption.IGNORE_CASE)
            .containsMatchIn(evidence)

    private fun containsNativeFatal(evidence: String): Boolean =
        Regex("SIGSEGV|SIGABRT|Fatal signal|Scudo|FATAL EXCEPTION|assertion failed|Access violation", RegexOption.IGNORE_CASE)
            .containsMatchIn(evidence)

    private fun containsFrameTimeout(evidence: String): Boolean =
        Regex("frame-timeout|no-produced-frame", RegexOption.IGNORE_CASE)
            .containsMatchIn(evidence)
}
