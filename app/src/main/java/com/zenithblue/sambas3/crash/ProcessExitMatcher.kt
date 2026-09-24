package com.zenithblue.sambas3.crash

import com.zenithblue.sambas3.logging.LogSessionManifest
import com.zenithblue.sambas3.session.EmulationSessionRecord
import kotlin.math.abs

/**
 * Robust correlation between emulation sessions and ApplicationExitInfo records.
 *
 * Rules per SambaS3 WORKER.md Section 6:
 * - Match specifically by PID and process timestamp window (never simply take the latest record).
 * - Distinguish secondary `:ppu_compile` worker exits from primary emulator process exits.
 * - Reject stale exit records where timestamp precedes session start (PID recycling).
 * - Match session ID markers in exit description or trace when present.
 */
object ProcessExitMatcher {

    private const val TIMESTAMP_TOLERANCE_MS = 5_000L // 5s clock tolerance before session start
    private const val SESSION_WINDOW_GRACE_MS = 60_000L // 60s grace period after last heartbeat

    fun matchSession(
        session: EmulationSessionRecord?,
        records: List<ProcessExitRecord>,
        packageName: String = "com.zenithblue.sambas3",
    ): ProcessExitRecord? {
        if (session == null || records.isEmpty()) return null
        return matchByCriteria(
            pid = session.pidAtSessionStart,
            startedAtMs = session.startedAtMs,
            lastHeartbeatMs = session.lastHeartbeatMs,
            sessionId = session.sessionId,
            records = records,
            packageName = packageName,
        )
    }

    fun matchManifest(
        manifest: LogSessionManifest?,
        records: List<ProcessExitRecord>,
        packageName: String = "com.zenithblue.sambas3",
    ): ProcessExitRecord? {
        if (manifest == null || records.isEmpty()) return null
        return matchByCriteria(
            pid = manifest.pid,
            startedAtMs = manifest.startedAtMs,
            lastHeartbeatMs = manifest.endedAtMs ?: manifest.startedAtMs,
            sessionId = manifest.sessionId,
            records = records,
            packageName = packageName,
        )
    }

    fun matchByCriteria(
        pid: Int?,
        startedAtMs: Long?,
        lastHeartbeatMs: Long?,
        sessionId: String?,
        records: List<ProcessExitRecord>,
        packageName: String = "com.zenithblue.sambas3",
    ): ProcessExitRecord? {
        if (records.isEmpty()) return null

        // 1. Separate primary emulator process exits from :ppu_compile workers.
        val primaryRecords = records.filter { record ->
            !record.isPpuCompile && (record.processName == packageName || !record.processName.contains(":"))
        }

        // 2. If PID is known (> 0), match STRICTLY by PID and timestamp.
        if (pid != null && pid > 0) {
            val pidCandidates = primaryRecords.filter { it.pid == pid }
            if (pidCandidates.isEmpty()) {
                // NEVER fall back to latest record of another PID.
                return null
            }

            // Filter out records that died before the session even started (PID reuse from old run).
            val minTime = (startedAtMs ?: 0L) - TIMESTAMP_TOLERANCE_MS
            val validTimeCandidates = pidCandidates.filter { it.timestamp >= minTime }
            if (validTimeCandidates.isEmpty()) {
                // The only records for this PID are from a prior life of this PID.
                return null
            }

            // If session ID is present in description or trace, verify it.
            if (!sessionId.isNullOrBlank()) {
                val explicitSessionMatch = validTimeCandidates.firstOrNull { candidate ->
                    candidate.description?.contains(sessionId) == true ||
                        candidate.trace?.contains(sessionId) == true
                }
                if (explicitSessionMatch != null) return explicitSessionMatch
            }

            // Select the record closest to lastHeartbeatMs or startedAtMs.
            val targetTime = lastHeartbeatMs ?: startedAtMs ?: 0L
            return validTimeCandidates.minByOrNull { abs(it.timestamp - targetTime) }
        }

        // 3. If PID is unknown (<= 0), attempt safe correlation by session ID or narrow timestamp window.
        if (!sessionId.isNullOrBlank()) {
            val sessionMatch = primaryRecords.firstOrNull { candidate ->
                candidate.description?.contains(sessionId) == true ||
                    candidate.trace?.contains(sessionId) == true
            }
            if (sessionMatch != null) return sessionMatch
        }

        // 4. Bounded time-window correlation when PID is missing.
        if (startedAtMs != null && startedAtMs > 0L) {
            val windowStart = startedAtMs - TIMESTAMP_TOLERANCE_MS
            val windowEnd = (lastHeartbeatMs ?: startedAtMs) + SESSION_WINDOW_GRACE_MS
            val windowMatches = primaryRecords.filter { it.timestamp in windowStart..windowEnd }

            // Only accept if unambiguous (exactly one record in the window).
            if (windowMatches.size == 1) {
                return windowMatches.first()
            }
        }

        return null
    }

    /**
     * Extracts only secondary :ppu_compile process exit records, optionally filtered
     * by minimum timestamp.
     */
    fun findPpuCompileExits(
        records: List<ProcessExitRecord>,
        minTimestampMs: Long? = null,
    ): List<ProcessExitRecord> {
        return records.filter { record ->
            record.isPpuCompile && (minTimestampMs == null || record.timestamp >= minTimestampMs)
        }
    }
}
