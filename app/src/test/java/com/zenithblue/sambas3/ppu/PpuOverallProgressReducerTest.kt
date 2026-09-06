package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuOverallProgressReducerTest {

    @Test
    fun reduceLiveProgress_maintainsFullTitleMonotonicProgress() {
        // Full title total is 233 (e.g. Demon's Souls)
        val initial = PpuOverallProgressReducer.reduceLiveProgress(
            titleTotal = 233,
            lastKnownCompleted = 0,
            workerTotal = 233,
            cachedBefore = 0,
            currentBatchCompiled = 8
        )
        assertEquals(233, initial.totalModules)
        assertEquals(8, initial.completedModules)
        assertEquals(3, initial.percent)

        // Mid-batch advance
        val midBatch = PpuOverallProgressReducer.reduceLiveProgress(
            titleTotal = 233,
            lastKnownCompleted = 8,
            workerTotal = 233,
            cachedBefore = 0,
            currentBatchCompiled = 16
        )
        assertEquals(233, midBatch.totalModules)
        assertEquals(16, midBatch.completedModules)
        assertEquals(6, midBatch.percent)
    }

    @Test
    fun reduceBatchFinished_advancesAndClamps() {
        // After batch 1 completes: 16 cached
        val batch1 = PpuOverallProgressReducer.reduceBatchFinished(
            titleTotal = 233,
            lastKnownCompleted = 0,
            workerTotal = 233,
            cachedAfter = 16
        )
        assertEquals(233, batch1.totalModules)
        assertEquals(16, batch1.completedModules)
        assertEquals(6, batch1.percent)

        // After batch 2 completes: 32 cached
        val batch2 = PpuOverallProgressReducer.reduceBatchFinished(
            titleTotal = 233,
            lastKnownCompleted = 16,
            workerTotal = 233,
            cachedAfter = 32
        )
        assertEquals(233, batch2.totalModules)
        assertEquals(32, batch2.completedModules)
        assertEquals(13, batch2.percent)

        // Final batch completes: 233 cached
        val finalBatch = PpuOverallProgressReducer.reduceBatchFinished(
            titleTotal = 233,
            lastKnownCompleted = 224,
            workerTotal = 233,
            cachedAfter = 233
        )
        assertEquals(233, finalBatch.totalModules)
        assertEquals(233, finalBatch.completedModules)
        assertEquals(100, finalBatch.percent)
    }

    @Test
    fun reduceLiveProgress_doesNotRegressOnProcessRestart() {
        // If a new process starts with cachedBefore=16, currentBatchCompiled=0,
        // it must not display 0 completed if lastKnownCompleted was 16.
        val restarted = PpuOverallProgressReducer.reduceLiveProgress(
            titleTotal = 233,
            lastKnownCompleted = 16,
            workerTotal = 233,
            cachedBefore = 16,
            currentBatchCompiled = 0
        )
        assertEquals(233, restarted.totalModules)
        assertEquals(16, restarted.completedModules)
        assertTrue(restarted.completedModules >= 16)
    }

    @Test
    fun nativeWindow_elfReset_accumulatesInsteadOfRewinding() {
        val window = PpuNativeProgressWindow()
        val first = window.absorb(27, 34)
        assertEquals(27, first.first)
        assertEquals(34, first.second)

        val afterReset = window.absorb(5, 34)
        assertEquals(32, afterReset.first)
        assertEquals(61, afterReset.second)
    }

    @Test
    fun nativeWindow_smallJitter_doesNotCountAsReset() {
        val window = PpuNativeProgressWindow()
        window.absorb(27, 34)
        val jitter = window.absorb(26, 34)
        // Drop of 1 is not an ELF reset, and high-water must not rewind.
        assertEquals(27, jitter.first)
        assertEquals(34, jitter.second)
    }

    @Test
    fun nativeWindow_resetKeepsHighWaterForNewWorker() {
        val window = PpuNativeProgressWindow()
        window.absorb(27, 34)
        window.absorb(5, 34)
        window.reset()
        val fresh = window.absorb(5, 34)
        assertEquals(32, fresh.first)
        assertEquals(61, fresh.second)
    }

    @Test
    fun nativeWindow_newBatchDoesNotRewindPastTitleHighWater() {
        val window = PpuNativeProgressWindow()
        window.absorb(52, 52)
        window.reset()
        val recycled = window.absorb(23, 33)
        assertEquals(52, recycled.first)
        assertEquals(52, recycled.second)
    }

    @Test
    fun nativeWindow_largeElfFunctionCountIsNotRewound() {
        val window = PpuNativeProgressWindow()
        window.absorb(52, 52)
        window.reset()
        val eboot = window.absorb(81, 35557)
        assertEquals(81, eboot.first)
        assertEquals(35557, eboot.second)
    }

    @Test
    fun liveDisplay_doesNotSitAtFullWhileStillCompiling() {
        val full = OverallProgress(totalModules = 52, completedModules = 52, percent = 100)
        val shown = PpuOverallProgressReducer.liveDisplay(full)
        assertEquals(52, shown.completedModules)
        assertEquals(53, shown.totalModules)
        assertTrue(shown.percent < 100)
    }

    @Test
    fun mergeMonotonic_doesNotRegressLiveUi() {
        val high = OverallProgress(totalModules = 34, completedModules = 27, percent = 79)
        val rewind = OverallProgress(totalModules = 34, completedModules = 5, percent = 14)
        val merged = PpuOverallProgressReducer.mergeMonotonic(high, rewind)
        assertEquals(27, merged.completedModules)
        assertEquals(34, merged.totalModules)
        assertTrue(merged.completedModules >= 27)
    }

    @Test
    fun reduceLiveProgress_growsTotalInsteadOfClampingCompleted() {
        val grown = PpuOverallProgressReducer.reduceLiveProgress(
            titleTotal = 34,
            lastKnownCompleted = 27,
            workerTotal = 68,
            cachedBefore = 27,
            currentBatchCompiled = 5,
        )
        assertEquals(32, grown.completedModules)
        assertEquals(68, grown.totalModules)
    }
}
