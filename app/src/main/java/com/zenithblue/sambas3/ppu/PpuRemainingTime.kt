package com.zenithblue.sambas3.ppu

/**
 * Remaining-time estimate for Kotlin-owned PPU batches.
 *
 * Native compile_progress remaining time is process-local and resets every
 * :ppu_compile recycle (~16 modules). This estimator lives in the main process
 * so wall-clock rate spans batches. It stays silent until a real compile rate
 * exists, ignores cache catch-up jumps, and publishes coarse labels so the UI
 * does not tick every second.
 */
class PpuRemainingTimeEstimator(
    private val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val eta = EvidenceEta()
    private var titleId: String? = null
    private var attempt: AttemptKey? = null
    private var baselineDone: Int = -1
    private var lastDone: Int = -1
    private var lastTotal: Int = -1
    private var lastMs: Long = 0L
    private var newlyCommitted: Int = 0
    private var lastCommitMs: Long = -1L
    private var publishedLabel: String? = null
    private var publishedMs: Long = 0L

    fun reset() {
        titleId = null
        attempt = null
        baselineDone = -1
        lastDone = -1
        lastTotal = -1
        lastMs = 0L
        newlyCommitted = 0
        lastCommitMs = -1L
        publishedLabel = null
        publishedMs = 0L
        eta.reset()
    }

    fun observe(
        nextTitleId: String?,
        done: Int,
        total: Int,
        active: Boolean,
        nowMs: Long = clockMs(),
    ): String? {
        if (!active) {
            reset()
            return null
        }
        if (!nextTitleId.isNullOrBlank() && titleId != null &&
            !nextTitleId.equals(titleId, ignoreCase = true)
        ) {
            reset()
        }
        if (!nextTitleId.isNullOrBlank()) titleId = nextTitleId

        if (total > 0 && done >= total) {
            reset()
            return null
        }

        if (baselineDone < 0) {
            baselineDone = done.coerceAtLeast(0)
            lastDone = baselineDone
            lastTotal = total
            lastMs = nowMs
            newlyCommitted = 0
            eta.estimateMs(
                nextKey = attemptKey(),
                total = total.takeIf { it > 0 }?.toLong(),
                validated = done.toLong(),
                newlyCommitted = 0,
                stage = PpuStage.DISCOVERING,
                nowMs = nowMs,
            )
            return null
        }

        if (lastTotal > 0 && total > lastTotal * TOTAL_JUMP_FACTOR &&
            total > lastTotal + TOTAL_JUMP_MIN
        ) {
            rebase(done, total, nowMs)
            return null
        }

        if (done < lastDone) {
            lastTotal = maxOf(lastTotal, total)
            return publishedLabel
        }

        val deltaDone = done - lastDone
        val deltaMs = (nowMs - lastMs).coerceAtLeast(0L)
        if (deltaDone >= CACHE_JUMP_MODULES && deltaMs < CACHE_JUMP_WINDOW_MS) {
            rebase(done, total, nowMs)
            return null
        }

        if (deltaDone > 0) {
            newlyCommitted += deltaDone
            lastCommitMs = nowMs
        }
        lastDone = done
        lastTotal = maxOf(lastTotal, total)
        lastMs = nowMs

        if (total <= 0) return publishedLabel

        val remainingGuess = (total - done).coerceAtLeast(0)
        if (remainingGuess == 0) {
            reset()
            return null
        }
        val minCompiled = if (remainingGuess > LARGE_REMAINING) LARGE_MIN_COMPILED else MIN_COMPILED_DELTA
        val minElapsed = if (remainingGuess > LARGE_REMAINING) LARGE_MIN_ELAPSED_MS else MIN_ELAPSED_MS
        if (newlyCommitted < minCompiled) return publishedLabel

        val key = attemptKey()
        val stage = PpuStage.COMPILING
        val estimate = eta.estimateMs(
            nextKey = key,
            total = total.toLong(),
            validated = done.toLong(),
            newlyCommitted = newlyCommitted.toLong(),
            stage = stage,
            nowMs = nowMs,
        )
        if (estimate == null) {
            if (lastCommitMs >= 0L && nowMs - lastCommitMs >= 60_000L) {
                publishedLabel = null
                publishedMs = 0L
                return null
            }
            return publishedLabel
        }
        if (newlyCommitted < minCompiled) return publishedLabel
        // Large ELF windows need a longer verified-commit window than EvidenceEta's 8s floor.
        if (remainingGuess > LARGE_REMAINING && minElapsed > 8_000L) {
            val endCommitAgeOk = estimate > 0
            if (!endCommitAgeOk) return publishedLabel
        }

        val candidate = PpuRemainingTime.format(estimate)
        if (shouldPublish(candidate, estimate)) {
            publishedLabel = candidate
            publishedMs = estimate
        }
        return publishedLabel
    }

    private fun rebase(done: Int, total: Int, nowMs: Long) {
        baselineDone = done.coerceAtLeast(0)
        lastDone = baselineDone
        lastTotal = total
        lastMs = nowMs
        newlyCommitted = 0
        lastCommitMs = -1L
        publishedLabel = null
        publishedMs = 0L
        eta.reset()
        attempt = null
        eta.estimateMs(
            nextKey = attemptKey(),
            total = total.takeIf { it > 0 }?.toLong(),
            validated = done.toLong(),
            newlyCommitted = 0,
            stage = PpuStage.DISCOVERING,
            nowMs = nowMs,
        )
    }

    private fun attemptKey(): AttemptKey {
        val existing = attempt
        if (existing != null) return existing
        val title = titleId?.takeIf { it.isNotBlank() } ?: "unknown"
        val created = AttemptKey(title, "eta-session", "eta-attempt", "eta-manifest", PpuKind.INSTALL)
        attempt = created
        return created
    }

    private fun shouldPublish(candidate: String, remainingMs: Long): Boolean {
        val last = publishedLabel ?: return true
        if (candidate == last) return false
        if (publishedMs <= 0L) return true
        // Slowdown: remaining grew enough to change the coarse bucket — show it.
        if (remainingMs > publishedMs) return true
        val delta = kotlin.math.abs(remainingMs - publishedMs)
        return delta >= LABEL_HOLD_MS
    }

    companion object {
        private const val MIN_COMPILED_DELTA = 2
        private const val MIN_ELAPSED_MS = 8_000L
        private const val LARGE_REMAINING = 1_000
        private const val LARGE_MIN_COMPILED = 8
        private const val LARGE_MIN_ELAPSED_MS = 30_000L
        private const val TOTAL_JUMP_FACTOR = 4
        private const val TOTAL_JUMP_MIN = 100
        private const val CACHE_JUMP_WINDOW_MS = 1_000L
        private const val CACHE_JUMP_MODULES = 4
        private const val LABEL_HOLD_MS = 25_000L
    }
}

object PpuRemainingTime {
    fun format(remainingMs: Long): String {
        val totalSec = (remainingMs / 1000L).coerceAtLeast(1L)
        return when {
            totalSec < 45L -> "less than a minute remaining"
            totalSec < 90L -> "about 1 min remaining"
            totalSec < 55L * 60L -> "~${(totalSec + 30L) / 60L} min remaining"
            else -> {
                val hours = totalSec / 3600L
                val minutes = ((totalSec % 3600L) + 30L) / 60L
                when {
                    hours >= 6L -> "several hours remaining"
                    minutes == 0L -> "~$hours hr remaining"
                    minutes >= 60L -> "~${hours + 1L} hr remaining"
                    else -> "~$hours hr $minutes min remaining"
                }
            }
        }
    }

    fun progressLine(detail: String, remainingLabel: String?): String {
        val rem = remainingLabel?.takeIf { it.isNotBlank() } ?: return detail
        if (detail.isBlank()) return rem
        return "$detail · $rem"
    }
}

object PpuRemainingTimeTracker {
    private val install = PpuRemainingTimeEstimator()
    private val runtime = PpuRemainingTimeEstimator()

    @Synchronized
    fun observeInstall(
        titleId: String?,
        done: Int,
        total: Int,
        active: Boolean,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): String? = install.observe(titleId, done, total, active, nowMs)

    @Synchronized
    fun observeRuntime(
        titleId: String?,
        done: Int,
        total: Int,
        active: Boolean,
        nowMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): String? = runtime.observe(titleId, done, total, active, nowMs)

    @Synchronized
    fun resetInstall() = install.reset()

    @Synchronized
    fun resetRuntime() = runtime.reset()
}
