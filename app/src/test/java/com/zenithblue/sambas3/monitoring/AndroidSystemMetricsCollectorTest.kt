package com.zenithblue.sambas3.monitoring

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class AndroidSystemMetricsCollectorTest {

    private val context: Context
        get() = mock(Context::class.java).also {
            `when`(it.applicationContext).thenReturn(it)
        }

    @Test
    fun testThermalHeadroomThrottlingAndCaching() {
        var currentTimeMs = 100_000L
        var headroomPollCount = 0
        var returnedHeadroom = 0.5f

        val collector = AndroidSystemMetricsCollector(
            context = context,
            timeProvider = { currentTimeMs },
            monotonicNanoProvider = { currentTimeMs * 1_000_000L },
            thermalHeadroomProvider = {
                headroomPollCount++
                returnedHeadroom
            }
        )

        collector.start()

        // First read at t = 100_000L should trigger thermal headroom poll
        val sample1 = collector.read()
        assertEquals(1, headroomPollCount)
        assertEquals(0.5f, sample1.thermalHeadroom)

        // Second read at t = 101_000L (1 second later): headroom should be cached, no new poll
        currentTimeMs += 1_000L
        returnedHeadroom = 0.8f
        val sample2 = collector.read()
        assertEquals(1, headroomPollCount) // count unchanged!
        assertEquals(0.5f, sample2.thermalHeadroom) // cached value returned!

        // Third read at t = 109_000L (9 seconds later): still within 15s window, no poll
        currentTimeMs += 8_000L
        val sample3 = collector.read()
        assertEquals(1, headroomPollCount)
        assertEquals(0.5f, sample3.thermalHeadroom)

        // Fourth read at t = 115_000L (15 seconds after first poll): should trigger new poll
        currentTimeMs += 6_000L
        val sample4 = collector.read()
        assertEquals(2, headroomPollCount)
        assertEquals(0.8f, sample4.thermalHeadroom)

        collector.stop()
    }

    @Test
    fun testThermalHeadroom_nonFinite_returnsNullWithoutRetry() {
        var currentTimeMs = 100_000L
        var headroomPollCount = 0

        val collector = AndroidSystemMetricsCollector(
            context = context,
            timeProvider = { currentTimeMs },
            monotonicNanoProvider = { currentTimeMs * 1_000_000L },
            thermalHeadroomProvider = {
                headroomPollCount++
                Float.NaN // non-finite
            }
        )

        collector.start()
        val sample = collector.read()
        // Single poll attempt, rejected because non-finite, no immediate retry
        assertEquals(1, headroomPollCount)
        assertNull(sample.thermalHeadroom)

        collector.stop()
    }

    @Test
    fun testDiagnosticPssCadence() {
        var currentTimeMs = 100_000L
        var pssPollCount = 0
        var returnedPss = 100_000_000L

        val collector = AndroidSystemMetricsCollector(
            context = context,
            timeProvider = { currentTimeMs },
            monotonicNanoProvider = { currentTimeMs * 1_000_000L },
            pssProvider = {
                pssPollCount++
                returnedPss
            }
        )

        collector.start()

        // First read at t = 100_000: initial PSS sample
        val sample1 = collector.read()
        assertEquals(1, pssPollCount)
        assertEquals(100_000_000L, sample1.processPssBytes)

        // Advance 3 seconds (t = 103_000): should NOT poll PSS (diagnostic cadence is 20s)
        currentTimeMs += 3_000L
        returnedPss = 120_000_000L
        val sample2 = collector.read()
        assertEquals(1, pssPollCount)
        assertEquals(100_000_000L, sample2.processPssBytes)

        // Advance to 19 seconds (t = 119_000): still no poll
        currentTimeMs += 16_000L
        val sample3 = collector.read()
        assertEquals(1, pssPollCount)
        assertEquals(100_000_000L, sample3.processPssBytes)

        // Advance to 20 seconds (t = 120_000): now polls diagnostic PSS
        currentTimeMs += 1_000L
        val sample4 = collector.read()
        assertEquals(2, pssPollCount)
        assertEquals(120_000_000L, sample4.processPssBytes)

        // On-demand request triggers immediate PSS refresh
        collector.requestPssSample()
        returnedPss = 130_000_000L
        val sample5 = collector.read()
        assertEquals(3, pssPollCount)
        assertEquals(130_000_000L, sample5.processPssBytes)

        collector.stop()
    }

    @Test
    fun testGpuLoadSourceIntegration() {
        val tempFile = File.createTempFile("gpu_busy_test", ".txt")
        tempFile.deleteOnExit()
        tempFile.writeText("43 128\n")

        var currentTimeMs = 50_000L
        val collector = AndroidSystemMetricsCollector(
            context = context,
            timeProvider = { currentTimeMs },
            monotonicNanoProvider = { currentTimeMs * 1_000_000L }
        )

        collector.start()
        collector.setGpuLoadSourceForTesting(
            GpuSource(
                path = tempFile.absolutePath,
                format = GpuFormat.KGSL_WINDOWED_PAIR,
                units = "%",
                scope = "device"
            )
        )

        val sample = collector.read()
        assertNotNull(sample.gpu)
        // 43 / 128 * 100 = 33.59375% -> rounded loadPercent is 34, precise is 33.59375
        assertEquals(34, sample.gpu?.loadPercent)
        assertEquals(33.59375, sample.gpu?.loadPercentPrecise ?: 0.0, 0.001)

        // Test invalid sample "0 0" -> load is unavailable (null)
        tempFile.writeText("0 0\n")
        currentTimeMs += 1_000L
        val sampleZero = collector.read()
        assertNull(sampleZero.gpu)

        // Test percentage scalar format "50%"
        collector.setGpuLoadSourceForTesting(
            GpuSource(
                path = tempFile.absolutePath,
                format = GpuFormat.PERCENTAGE_SCALAR,
                units = "%",
                scope = "device"
            )
        )
        tempFile.writeText("50%\n")
        currentTimeMs += 1_000L
        val samplePct = collector.read()
        assertNotNull(samplePct.gpu)
        assertEquals(50, samplePct.gpu?.loadPercent)
        assertEquals(50.0, samplePct.gpu?.loadPercentPrecise ?: 0.0, 0.001)

        collector.stop()
        tempFile.delete()
    }
}
