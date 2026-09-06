package com.zenithblue.sambas3.ppu

data class OverallProgress(
    val totalModules: Int,
    val completedModules: Int,
    val percent: Int
)

/**
 * Native g_progr_pdone/ptotal is the current ELF window. When that ELF
 * finishes, RPCSX subtracts the window and the next PRX starts at 0 again.
 * A fresh :ppu_compile process does the same. Fold those resets into a
 * monotonic count so Launch Center does not flicker 27/34 → 5/34.
 */
class PpuNativeProgressWindow {
    private var lastNativeDone: Int = 0
    private var offset: Int = 0
    private var highDone: Int = 0
    private var highTotal: Int = 0

    /**
     * New :ppu_compile process: native pdone/ptotal starts over.
     * Keep title high-water so Launch Center cannot rewind 52/52 → 23/33.
     */
    fun reset() {
        lastNativeDone = 0
        offset = 0
    }

    fun absorb(nativeDone: Int, nativeTotal: Int): Pair<Int, Int> {
        val done = nativeDone.coerceAtLeast(0)
        val total = nativeTotal.coerceAtLeast(0)
        if (lastNativeDone > 0 && lastNativeDone - done >= RESET_DROP) {
            offset += lastNativeDone
        }
        lastNativeDone = done
        val absDone = maxOf(highDone, offset + done)
        val absTotal = maxOf(highTotal, offset + total, absDone)
        highDone = absDone
        highTotal = absTotal
        return absDone to absTotal
    }

    companion object {
        const val RESET_DROP = 3
    }
}

object PpuOverallProgressReducer {
    fun reduceLiveProgress(
        titleTotal: Int,
        lastKnownCompleted: Int,
        workerTotal: Int,
        cachedBefore: Int,
        currentBatchCompiled: Int
    ): OverallProgress {
        val liveDone = (cachedBefore + currentBatchCompiled).coerceAtLeast(0)
        val completed = maxOf(lastKnownCompleted, liveDone).coerceAtLeast(0)
        val effectiveTotal = maxOf(titleTotal, workerTotal, completed)
        val pct = if (effectiveTotal > 0) (completed * 100 / effectiveTotal).coerceIn(0, 100) else 0
        return OverallProgress(effectiveTotal, completed, pct)
    }

    fun reduceBatchFinished(
        titleTotal: Int,
        lastKnownCompleted: Int,
        workerTotal: Int,
        cachedAfter: Int
    ): OverallProgress {
        val completed = maxOf(lastKnownCompleted, cachedAfter).coerceAtLeast(0)
        val effectiveTotal = maxOf(titleTotal, workerTotal, completed)
        val pct = if (effectiveTotal > 0) (completed * 100 / effectiveTotal).coerceIn(0, 100) else 0
        return OverallProgress(effectiveTotal, completed, pct)
    }

    /** Live UI must not sit at N/N 100% while the worker is still compiling more ELFs. */
    fun liveDisplay(progress: OverallProgress): OverallProgress {
        if (progress.totalModules > 0 && progress.completedModules >= progress.totalModules) {
            val total = progress.completedModules + 1
            return OverallProgress(total, progress.completedModules, (progress.completedModules * 100 / total).coerceIn(0, 99))
        }
        return progress
    }

    fun mergeMonotonic(previous: OverallProgress?, next: OverallProgress): OverallProgress {
        if (previous == null || previous.totalModules <= 0 && previous.completedModules <= 0) {
            return next
        }
        val completed = maxOf(previous.completedModules, next.completedModules)
        val total = maxOf(previous.totalModules, next.totalModules, completed)
        val pct = if (total > 0) (completed * 100 / total).coerceIn(0, 100) else next.percent
        return OverallProgress(total, completed, pct)
    }
}
