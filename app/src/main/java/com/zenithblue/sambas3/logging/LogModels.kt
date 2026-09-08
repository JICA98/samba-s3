package com.zenithblue.sambas3.logging

import com.zenithblue.sambas3.LogLevel
import com.zenithblue.sambas3.LogSource

enum class LogSourceKind(val label: String) {
    APP_ANDROID("App / Android"),
    RPCSX_BACKEND("RPCSX / RPCS3"),
    RPCS3_CORE("RPCS3 core"),
    GAME("Game"),
    VULKAN("Vulkan"),
    TURNIP_GPU("Turnip / GPU"),
    NATIVE_STDOUT("Native stdout"),
    NATIVE_STDERR("Native stderr"),
    SHADER("Shader"),
    CRASH("Crash"),
    OTHER("Other"),
    LEGACY("Legacy logs"),
}

sealed interface LogSourceStatus {
    data object Waiting : LogSourceStatus
    data class Active(val lines: Long, val bytes: Long) : LogSourceStatus
    data class Quiet(val lines: Long = 0L) : LogSourceStatus
    data class Unavailable(val reason: String) : LogSourceStatus
    data class PermissionDenied(val reason: String) : LogSourceStatus
    data class ParseError(val reason: String) : LogSourceStatus
    data class Ended(val lines: Long, val bytes: Long) : LogSourceStatus
}

data class UnifiedLogEntry(
    val sequence: Long,
    val sessionId: String?,
    val timestampMs: Long?,
    val timestampText: String?,
    val monotonicNs: Long?,
    val level: LogLevel,
    val tag: String?,
    val message: String,
    val source: LogSourceKind,
    val artifactId: String? = null,
    val raw: Boolean = false,
)

data class LogArtifact(
    val id: String,
    val source: LogSourceKind,
    val path: String,
    val detectedAtMs: Long,
    val bytes: Long,
    val compressed: Boolean,
    val liveTailSupported: Boolean,
    val finalStatus: String,
    val relativePath: String? = null,
    val originalFilename: String = "",
    val role: String = "raw",
    val producer: String? = null,
    val generation: String? = null,
    val sealed: Boolean = false,
    val sha256: String? = null,
    val encoding: String = "utf-8",
    val firstCursor: Long? = null,
    val lastCursor: Long? = null,
)

enum class LogSessionTerminal {
    RUNNING,
    CLEAN_STOP,
    FAILED,
    CRASHED,
    INTERRUPTED,
}

data class LogSessionManifest(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val gamePath: String,
    val titleId: String?,
    val gameTitleSnapshot: String,
    val gameIconSnapshotPath: String?,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val terminalState: LogSessionTerminal = LogSessionTerminal.RUNNING,
    val stopReason: String? = null,
    val bootMode: String? = null,
    val appVersion: String? = null,
    val coreBuildId: String? = null,
    val pid: Int? = null,
    val driverLabel: String? = null,
    val artifacts: List<LogArtifact> = emptyList(),
    val droppedLines: Long = 0L,
    val revision: Long = 1L,
    val captureState: CaptureState = CaptureState.RECORDING,
    val captureError: String? = null,
    val processInstanceId: String? = null,
    val producerEpoch: String? = null,
    val displayDroppedLines: Long = 0L,
    val persistenceDroppedLines: Long = 0L,
    val appliedDriverLabel: String? = null,
    val unknownTerminalRaw: String? = null,
)

fun LogSourceKind.toLegacySource(): LogSource = when (this) {
    LogSourceKind.APP_ANDROID -> LogSource.APP
    LogSourceKind.RPCSX_BACKEND, LogSourceKind.RPCS3_CORE, LogSourceKind.GAME -> LogSource.RPCSX
    LogSourceKind.VULKAN -> LogSource.VULKAN
    LogSourceKind.TURNIP_GPU -> LogSource.DRIVER
    LogSourceKind.CRASH -> LogSource.OTHER
    else -> LogSource.OTHER
}

fun LogSourceKind.statusLabel(status: LogSourceStatus): String = when (status) {
    is LogSourceStatus.Waiting -> "WAITING"
    is LogSourceStatus.Active -> "LIVE · ${status.lines} lines"
    is LogSourceStatus.Quiet -> "QUIET · ${status.lines} lines"
    is LogSourceStatus.Unavailable -> "UNAVAILABLE · ${status.reason}"
    is LogSourceStatus.PermissionDenied -> "PERMISSION DENIED · ${status.reason}"
    is LogSourceStatus.ParseError -> "ERROR · ${status.reason}"
    is LogSourceStatus.Ended -> "ENDED · ${status.lines} lines"
}
