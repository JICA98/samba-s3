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
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var titleId: String? = null
    private var baselineDone: Int = -1
    private var baselineMs: Long = 0L
    private var lastDone: Int = -1
    private var lastTotal: Int = -1
    private var lastMs: Long = 0L
    private var emaRemainingMs: Double? = null
    private var publishedLabel: String? = null
    private var publishedMs: Long = 0L

    fun reset() {
        titleId = null
        baselineDone = -1
        baselineMs = 0L
        lastDone = -1
        lastTotal = -1
        lastMs = 0L
        emaRemainingMs = null
        publishedLabel = null
        publishedMs = 0L
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
            baselineMs = nowMs
            lastDone = baselineDone
            lastTotal = total
            lastMs = nowMs
            return null
        }

        // Native ptotal can jump from a small PRX window (52) to a large ELF
        // function count (35557). Rebase so a 4s/module rate is not applied
        // to tens of thousands of remaining LLVM objects.
        if (lastTotal > 0 && total > lastTotal * TOTAL_JUMP_FACTOR &&
            total > lastTotal + TOTAL_JUMP_MIN
        ) {
            baselineDone = done.coerceAtLeast(0)
            baselineMs = nowMs
            lastDone = baselineDone
            lastTotal = total
            lastMs = nowMs
            emaRemainingMs = null
            publishedLabel = null
            publishedMs = 0L
            return null
        }

        if (done < lastDone) {
            lastMs = nowMs
            lastTotal = maxOf(lastTotal, total)
            return publishedLabel
        }

        val deltaDone = done - lastDone
        val deltaMs = (nowMs - lastMs).coerceAtLeast(0L)
        // Instant jumps are cache scans in a fresh worker, not compile rate.
        if (deltaDone >= CACHE_JUMP_MODULES && deltaMs < CACHE_JUMP_WINDOW_MS) {
            baselineDone = done
            baselineMs = nowMs
            lastDone = done
            lastTotal = total
            lastMs = nowMs
            emaRemainingMs = null
            publishedLabel = null
            publishedMs = 0L
            return null
        }

        lastDone = done
        lastTotal = maxOf(lastTotal, total)
        lastMs = nowMs

        if (total <= 0) return publishedLabel

        val compiled = done - baselineDone
        val elapsed = nowMs - baselineMs
        val remainingGuess = (total - done).coerceAtLeast(0)
        val minCompiled = if (remainingGuess > LARGE_REMAINING) LARGE_MIN_COMPILED else MIN_COMPILED_DELTA
        val minElapsed = if (remainingGuess > LARGE_REMAINING) LARGE_MIN_ELAPSED_MS else MIN_ELAPSED_MS
        if (compiled < minCompiled || elapsed < minElapsed) {
            return publishedLabel
        }

        val remainingModules = (total - done).coerceAtLeast(0)
        if (remainingModules == 0) {
            reset()
            return null
        }

        val msPerModule = (elapsed.toDouble() / compiled.toDouble())
            .coerceIn(MS_PER_MODULE_MIN, MS_PER_MODULE_MAX)
        val rawRemaining = remainingModules * msPerModule
        val ema = emaRemainingMs
        val smoothed = if (ema == null) {
            rawRemaining
        } else {
            EMA_ALPHA * rawRemaining + (1.0 - EMA_ALPHA) * ema
        }
        emaRemainingMs = smoothed

        val candidate = PpuRemainingTime.format(smoothed.toLong())
        if (shouldPublish(candidate, smoothed.toLong())) {
            publishedLabel = candidate
            publishedMs = smoothed.toLong()
        }
        return publishedLabel
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
        private const val MS_PER_MODULE_MIN = 250.0
        private const val MS_PER_MODULE_MAX = 12.0 * 60_000.0
        private const val EMA_ALPHA = 0.25
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
        nowMs: Long = System.currentTimeMillis(),
    ): String? = install.observe(titleId, done, total, active, nowMs)

    @Synchronized
    fun observeRuntime(
        titleId: String?,
        done: Int,
        total: Int,
        active: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): String? = runtime.observe(titleId, done, total, active, nowMs)

    @Synchronized
    fun resetInstall() = install.reset()

    @Synchronized
    fun resetRuntime() = runtime.reset()
}
