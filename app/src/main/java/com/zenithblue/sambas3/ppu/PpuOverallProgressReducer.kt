package com.zenithblue.sambas3.ppu

data class OverallProgress(
    val totalModules: Int,
    val completedModules: Int,
    val percent: Int
)

object PpuOverallProgressReducer {
    fun reduceLiveProgress(
        titleTotal: Int,
        lastKnownCompleted: Int,
        workerTotal: Int,
        cachedBefore: Int,
        currentBatchCompiled: Int
    ): OverallProgress {
        val effectiveTotal = when {
            titleTotal > 0 -> titleTotal
            workerTotal > 0 -> workerTotal
            else -> 0
        }
        val liveDone = cachedBefore + currentBatchCompiled
        val completed = maxOf(lastKnownCompleted, liveDone).let {
            if (effectiveTotal > 0) it.coerceIn(0, effectiveTotal) else it
        }
        val pct = if (effectiveTotal > 0) (completed * 100 / effectiveTotal).coerceIn(0, 100) else 0
        return OverallProgress(effectiveTotal, completed, pct)
    }

    fun reduceBatchFinished(
        titleTotal: Int,
        lastKnownCompleted: Int,
        workerTotal: Int,
        cachedAfter: Int
    ): OverallProgress {
        val effectiveTotal = when {
            titleTotal > 0 -> titleTotal
            workerTotal > 0 -> workerTotal
            else -> 0
        }
        val completed = maxOf(lastKnownCompleted, cachedAfter).let {
            if (effectiveTotal > 0) it.coerceIn(0, effectiveTotal) else it
        }
        val pct = if (effectiveTotal > 0) (completed * 100 / effectiveTotal).coerceIn(0, 100) else 0
        return OverallProgress(effectiveTotal, completed, pct)
    }
}
