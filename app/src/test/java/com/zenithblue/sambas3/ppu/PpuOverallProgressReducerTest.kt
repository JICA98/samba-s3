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
}
