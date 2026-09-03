package com.zenithblue.sambas3.ppu

import kotlinx.serialization.Serializable

enum class PpuSessionPhase {
    CREATED,
    WAITING_FOR_WORKER,
    BATCH_RUNNING,
    WAITING_FOR_WORKER_EXIT,
    MORE_WORK,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELED,
    INTERRUPTED,
}

@Serializable
data class PpuInstallSession(
    val sessionId: Long,
    val jobId: Long,
    val titleId: String,
    val gamePath: String,
    val manifestKey: String,
    val totalModules: Int = 0,
    val completedModules: Int = 0,
    val batchIndex: Int = 0,
    val batchSize: Int = 16,
    val phase: PpuSessionPhase = PpuSessionPhase.CREATED,
    val lastWorkerPid: Int? = null,
    val lastWorkerInstanceId: String? = null,
    val crashCount: Int = 0,
    val updatedMs: Long = System.currentTimeMillis()
)
