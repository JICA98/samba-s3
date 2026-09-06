package com.zenithblue.sambas3.logging

import kotlinx.coroutines.flow.StateFlow

interface LogSourceAdapter {
    val kind: LogSourceKind
    val status: StateFlow<LogSourceStatus>
    suspend fun start(session: LogSessionContext, emit: suspend (UnifiedLogEntry) -> Unit)
    suspend fun stop()
}

data class LogSessionContext(
    val sessionId: String?,
    val roots: List<java.io.File>,
    val sessionDir: java.io.File?,
    val startedAtMs: Long,
)
