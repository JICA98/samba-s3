package com.zenithblue.sambas3.ppu

import java.util.UUID

/**
 * Typed PPU preparation contract. Native snapshots must contain absolute,
 * deduplicated counts for one manifest. A process-local counter or a
 * directory-entry count does not meet this contract.
 */
enum class PpuKind { INSTALL, PRELAUNCH }

enum class PpuStage { DISCOVERING, COMPILING, VERIFYING, STOPPING, STOPPED, READY, FAILED }

enum class PpuBatchOutcome { MORE_WORK, ALL_COMPLETE, FAILED, CANCELED }

enum class PpuAcceptance { ACCEPTED, STALE, INVALID }

data class AttemptKey(
    val titleId: String,
    val sessionId: String,
    val attemptId: String,
    val manifestId: String,
    val kind: PpuKind,
) {
    init {
        require(listOf(titleId, sessionId, attemptId, manifestId).all { it.isNotBlank() })
    }

    companion object {
        fun fresh(titleId: String, manifestId: String, kind: PpuKind): AttemptKey {
            val session = UUID.randomUUID().toString()
            return AttemptKey(
                titleId = titleId,
                sessionId = session,
                attemptId = UUID.randomUUID().toString(),
                manifestId = manifestId.ifBlank { "unknown" },
                kind = kind,
            )
        }
    }
}

data class WorkerKey(val attempt: AttemptKey, val batchIndex: Int, val instanceId: String) {
    init { require(batchIndex >= 0 && instanceId.isNotBlank()) }
}

data class PpuSnapshot(
    val worker: WorkerKey,
    val sequence: Long,
    val discoveryComplete: Boolean,
    val requiredObjects: Long?,
    val validatedObjects: Long,
    val newlyCommittedObjects: Long,
)

data class PpuBatchResult(
    val finalSnapshot: PpuSnapshot,
    val outcome: PpuBatchOutcome,
    val auditComplete: Boolean,
)

data class PpuProgressView(
    val stage: PpuStage,
    val text: String,
    val percent: Int?,
    val retryAllowed: Boolean,
)

/**
 * All mutations are serialized. Android integration uses the same owner
 * for coordinator, Binder events, notification actions, and lifecycle events.
 */
class PreparationContract(val attempt: AttemptKey) {
    var stage: PpuStage = PpuStage.DISCOVERING
        private set
    var error: String? = null
        private set
    private var worker: WorkerKey? = null
    private var lastSequence = -1L
    private var pending: PpuBatchResult? = null
    private var required: Long? = null
    private var validated = 0L
    private var committed = 0L
    private var lastBatchIndex = -1

    @Synchronized
    fun attach(next: WorkerKey): Boolean {
        if (next.attempt != attempt || worker != null || next.batchIndex <= lastBatchIndex) return false
        if (stage !in setOf(PpuStage.DISCOVERING, PpuStage.COMPILING)) return false
        worker = next
        lastBatchIndex = next.batchIndex
        lastSequence = -1L
        pending = null
        return true
    }

    @Synchronized
    fun progress(s: PpuSnapshot): PpuAcceptance {
        if (s.worker != worker || s.worker.attempt != attempt || s.sequence <= lastSequence) return PpuAcceptance.STALE
        if (stage == PpuStage.STOPPING || pending != null) return PpuAcceptance.STALE
        return acceptCounts(s)
    }

    private fun invalid(why: String): PpuAcceptance {
        error = why
        stage = PpuStage.FAILED
        return PpuAcceptance.INVALID
    }

    private fun acceptCounts(s: PpuSnapshot): PpuAcceptance {
        if (stage == PpuStage.FAILED) return PpuAcceptance.STALE
        if (s.sequence < 0 || s.validatedObjects < 0 || s.newlyCommittedObjects < 0) return invalid("negative_count")
        if (s.discoveryComplete != (s.requiredObjects != null)) return invalid("inventory_not_sealed")
        if (s.requiredObjects != null && s.requiredObjects < 0) return invalid("negative_total")
        if (required != null && s.requiredObjects != required) return invalid("sealed_inventory_changed")
        if (s.requiredObjects != null && s.validatedObjects > s.requiredObjects) return invalid("validated_exceeds_required")
        if (s.validatedObjects < validated || s.newlyCommittedObjects < committed) return invalid("cache_generation_changed")
        if (s.newlyCommittedObjects > s.validatedObjects) return invalid("committed_exceeds_validated")
        required = s.requiredObjects
        validated = s.validatedObjects
        committed = s.newlyCommittedObjects
        lastSequence = s.sequence
        stage = when {
            required == null -> PpuStage.DISCOVERING
            validated == required -> PpuStage.VERIFYING
            else -> PpuStage.COMPILING
        }
        return PpuAcceptance.ACCEPTED
    }

    @Synchronized
    fun result(r: PpuBatchResult): PpuAcceptance {
        val s = r.finalSnapshot
        if (s.worker != worker || s.worker.attempt != attempt || s.sequence <= lastSequence) return PpuAcceptance.STALE
        if (stage == PpuStage.STOPPING || pending != null) return PpuAcceptance.STALE
        val accepted = acceptCounts(s)
        if (accepted != PpuAcceptance.ACCEPTED) return accepted
        if (r.outcome == PpuBatchOutcome.ALL_COMPLETE &&
            (!r.auditComplete || required == null || validated != required)
        ) {
            return invalid("unproved_completion")
        }
        pending = r
        stage = PpuStage.VERIFYING
        return PpuAcceptance.ACCEPTED
    }

    @Synchronized
    fun requestStop(key: AttemptKey): Boolean {
        if (key != attempt || stage in setOf(PpuStage.READY, PpuStage.STOPPED)) return false
        stage = if (worker == null) PpuStage.STOPPED else PpuStage.STOPPING
        return true
    }

    /** Call only on a verified exit for this instance, never merely after killProcess(). */
    @Synchronized
    fun observedWorkerExit(key: WorkerKey): Boolean {
        if (worker != key) return false
        worker = null
        val result = pending
        pending = null
        stage = when {
            stage == PpuStage.STOPPING -> PpuStage.STOPPED
            error != null -> PpuStage.FAILED
            result == null -> {
                error = "worker_exited_without_result"
                PpuStage.FAILED
            }
            result.outcome == PpuBatchOutcome.ALL_COMPLETE -> PpuStage.READY
            result.outcome == PpuBatchOutcome.CANCELED -> PpuStage.STOPPED
            result.outcome == PpuBatchOutcome.FAILED -> PpuStage.FAILED
            required == null -> PpuStage.DISCOVERING
            else -> PpuStage.COMPILING
        }
        return true
    }

    @Synchronized
    fun view(): PpuProgressView = PpuProgressPresentation.view(stage, validated, required, worker != null, error)

    @Synchronized
    fun currentWorker(): WorkerKey? = worker

    @Synchronized
    fun validatedObjects(): Long = validated

    @Synchronized
    fun requiredObjects(): Long? = required

    @Synchronized
    fun newlyCommitted(): Long = committed
}

/** Consecutive completed batches with no verified work and no discovery advancement. */
class NoProgressGuard(private val limit: Int = 3) {
    init { require(limit > 0) }
    private var consecutive = 0
    fun completedBatch(newlyValidated: Long, discoveryAdvanced: Boolean): Boolean {
        require(newlyValidated >= 0)
        consecutive = if (newlyValidated > 0 || discoveryAdvanced) 0 else consecutive + 1
        return consecutive >= limit
    }
}

/**
 * ETA uses verified new commits, not cached catch-up or synthetic percentages.
 * Caller passes a monotonic clock (Android SystemClock.elapsedRealtime()).
 * Staleness hides an estimate; it does not cancel the compiler.
 */
class EvidenceEta(private val staleAfterMs: Long = 60_000L) {
    private data class Point(val timeMs: Long, val committed: Long)
    private var key: AttemptKey? = null
    private var baseline: Point? = null
    private var lastCommit: Point? = null
    private var latestNow = -1L
    private var latestCommitted = 0L

    fun reset() {
        key = null
        baseline = null
        lastCommit = null
        latestNow = -1L
        latestCommitted = 0L
    }

    fun estimateMs(
        nextKey: AttemptKey,
        total: Long?,
        validated: Long,
        newlyCommitted: Long,
        stage: PpuStage,
        nowMs: Long,
    ): Long? {
        require(nowMs >= 0 && validated >= 0 && newlyCommitted >= 0)
        if (key != nextKey || nowMs < latestNow || newlyCommitted < latestCommitted) reset()
        key = nextKey
        latestNow = nowMs
        if (baseline == null) baseline = Point(nowMs, newlyCommitted)
        if (newlyCommitted > latestCommitted) lastCommit = Point(nowMs, newlyCommitted)
        latestCommitted = newlyCommitted
        if (stage != PpuStage.COMPILING || total == null || total <= validated || validated < newlyCommitted) return null
        val base = baseline ?: return null
        val end = lastCommit ?: return null
        if (nowMs - end.timeMs >= staleAfterMs) return null
        val n = end.committed - base.committed
        val elapsed = end.timeMs - base.timeMs
        if (n < 2 || elapsed < 8_000L) return null
        val estimate = (total - validated).toDouble() * elapsed.toDouble() / n.toDouble()
        return estimate.coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    }
}

object PpuProgressPresentation {
    fun view(
        stage: PpuStage,
        validated: Long,
        required: Long?,
        workerAlive: Boolean,
        error: String? = null,
    ): PpuProgressView {
        val text = when (stage) {
            PpuStage.STOPPING -> "Stopping PPU — waiting for worker exit"
            PpuStage.STOPPED -> "Stopped — Retry resumes validated cache"
            PpuStage.READY -> "Ready"
            PpuStage.FAILED -> if (workerAlive) "Failure — stopping worker" else "Failed — retry available"
            PpuStage.DISCOVERING -> "Discovering required PPU objects… $validated objects validated"
            PpuStage.VERIFYING -> if (required != null) {
                "Verifying cache… $validated of $required objects validated"
            } else {
                "Releasing compiler process…"
            }
            PpuStage.COMPILING -> "Compiling PPU objects… $validated of $required validated"
        }
        val percent = when {
            stage == PpuStage.READY -> 100
            stage != PpuStage.COMPILING || required == null || required == 0L -> null
            else -> ((validated.toDouble() / required.toDouble()) * 100.0).toInt().coerceIn(0, 99)
        }
        return PpuProgressView(
            stage = stage,
            text = text,
            percent = percent,
            retryAllowed = !workerAlive && stage in setOf(PpuStage.STOPPED, PpuStage.FAILED),
        )
    }
}
