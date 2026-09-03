package com.zenithblue.sambas3.ppu

object PpuBatchPolicy {
    const val DEFAULT_BATCH_SIZE = 16
    const val MIN_BATCH_SIZE = 1
    const val MAX_RETRIES_PER_BATCH_SIZE = 2

    fun nextBatchSizeOnFailure(currentSize: Int): Int {
        return when {
            currentSize > 8 -> 8
            currentSize > 4 -> 4
            currentSize > 2 -> 2
            else -> 1
        }
    }

    fun shouldFailPermanently(consecutiveFailuresAtMinSize: Int): Boolean {
        return consecutiveFailuresAtMinSize >= MAX_RETRIES_PER_BATCH_SIZE
    }
}
