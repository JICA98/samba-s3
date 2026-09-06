package com.zenithblue.sambas3.ppu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpuRemainingTimeTest {

    @Test
    fun format_usesCoarseBuckets() {
        assertEquals("less than a minute remaining", PpuRemainingTime.format(20_000))
        assertEquals("about 1 min remaining", PpuRemainingTime.format(70_000))
        assertEquals("~4 min remaining", PpuRemainingTime.format(4 * 60_000L))
        assertEquals("~8 min remaining", PpuRemainingTime.format((7 * 60 + 40) * 1000L))
        assertEquals("~1 hr remaining", PpuRemainingTime.format(60 * 60_000L))
        assertEquals("~1 hr 20 min remaining", PpuRemainingTime.format((80 * 60) * 1000L))
        assertEquals("several hours remaining", PpuRemainingTime.format(264L * 3600_000L))
        assertEquals("several hours remaining", PpuRemainingTime.format(408L * 3600_000L))
    }

    @Test
    fun progressLine_appendsRemainingOnlyWhenPresent() {
        assertEquals("module 25 of 71", PpuRemainingTime.progressLine("module 25 of 71", null))
        assertEquals(
            "module 25 of 71 · ~8 min remaining",
            PpuRemainingTime.progressLine("module 25 of 71", "~8 min remaining"),
        )
    }

    @Test
    fun observe_staysSilentUntilRateExists() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        assertNull(est.observe("BLUS31584", 0, 71, true, now))
        now += 3_000
        assertNull(est.observe("BLUS31584", 1, 71, true, now))
        now += 3_000
        assertNull(est.observe("BLUS31584", 1, 71, true, now))
    }

    @Test
    fun observe_publishesAfterTwoModulesAndEightSeconds() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS31584", 0, 71, true, now)
        now += 4_000
        est.observe("BLUS31584", 1, 71, true, now)
        now += 4_000
        val label = est.observe("BLUS31584", 2, 71, true, now)
        assertNotNull(label)
        assertTrue(label!!.contains("remaining"))
        // 2 modules in 8s → 4s/module × 69 remaining ≈ 4.6 min → ~5 min
        assertEquals("~5 min remaining", label)
    }

    @Test
    fun observe_ignoresCacheCatchUpJump() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS31584", 0, 71, true, now)
        now += 200
        assertNull(est.observe("BLUS31584", 20, 71, true, now))
        now += 8_000
        val label = est.observe("BLUS31584", 22, 71, true, now)
        assertNotNull(label)
        // baseline rebased at 20; 2 modules in 8s × 49 remaining ≈ 3.3 min
        assertEquals("~3 min remaining", label)
    }

    @Test
    fun observe_survivesBatchRecycleGap() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS31584", 0, 71, true, now)
        now += 40_000
        est.observe("BLUS31584", 16, 71, true, now)
        now += 2_000 // process recycle pause, done unchanged
        est.observe("BLUS31584", 16, 71, true, now)
        now += 10_000
        val label = est.observe("BLUS31584", 18, 71, true, now)
        assertNotNull(label)
        assertTrue(label!!.contains("remaining"))
        assertFalse(label.contains("less than a minute"))
    }

    @Test
    fun observe_resetsOnTitleChangeAndInactive() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS31584", 0, 71, true, now)
        now += 8_000
        est.observe("BLUS31584", 2, 71, true, now)
        now += 1_000
        assertNull(est.observe("BCUS98111", 2, 80, true, now))
        now += 8_000
        assertNotNull(est.observe("BCUS98111", 4, 80, true, now))
        assertNull(est.observe("BCUS98111", 4, 80, active = false, now))
        now += 8_000
        assertNull(est.observe("BCUS98111", 6, 80, true, now))
    }

    @Test
    fun observe_rebasesWhenNativeTotalJumpsToLargeElf() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS30109", 50, 60, true, now)
        now += 8_000
        est.observe("BLUS30109", 52, 60, true, now)
        now += 1_000
        // EBOOT function window: 81 of 35557 must not keep a 264hr label.
        assertNull(est.observe("BLUS30109", 81, 35557, true, now))
        now += 8_000
        assertNull(est.observe("BLUS30109", 83, 35557, true, now))
    }

    @Test
    fun observe_clearsWhenComplete() {
        var now = 1_000_000L
        val est = PpuRemainingTimeEstimator { now }
        est.observe("BLUS31584", 60, 71, true, now)
        now += 8_000
        est.observe("BLUS31584", 62, 71, true, now)
        assertNull(est.observe("BLUS31584", 71, 71, true, now + 1_000))
    }
}
