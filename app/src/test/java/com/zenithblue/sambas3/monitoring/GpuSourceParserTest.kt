package com.zenithblue.sambas3.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSourceParserTest {

    private val parser = GpuSourceParser()

    @Test
    fun testKgslWindowedPair_50_100() {
        val result = parser.parse("50 100", GpuFormat.KGSL_WINDOWED_PAIR)
        assertNotNull(result)
        assertEquals(50.0, result!!, 0.0001)
    }

    @Test
    fun testKgslWindowedPair_43_128() {
        val result = parser.parse("43 128", GpuFormat.KGSL_WINDOWED_PAIR)
        assertNotNull(result)
        // 43 / 128 * 100 = 33.59375% ~ 33.594%
        assertEquals(33.59375, result!!, 0.001)
    }

    @Test
    fun testKgslWindowedPair_0_0_isUnavailable() {
        val result = parser.parse("0 0", GpuFormat.KGSL_WINDOWED_PAIR)
        assertNull(result)
    }

    @Test
    fun testKgslWindowedPair_zeroNumerator_valid() {
        val result = parser.parse("0 100", GpuFormat.KGSL_WINDOWED_PAIR)
        assertNotNull(result)
        assertEquals(0.0, result!!, 0.0001)
    }

    @Test
    fun testKgslWindowedPair_busyGreaterThanTotal_rejected() {
        val result = parser.parse("120 100", GpuFormat.KGSL_WINDOWED_PAIR)
        assertNull(result)
    }

    @Test
    fun testKgslWindowedPair_negativeValues_rejected() {
        assertNull(parser.parse("-10 100", GpuFormat.KGSL_WINDOWED_PAIR))
        assertNull(parser.parse("10 -100", GpuFormat.KGSL_WINDOWED_PAIR))
        assertNull(parser.parse("-5 -10", GpuFormat.KGSL_WINDOWED_PAIR))
    }

    @Test
    fun testKgslWindowedPair_zeroDenominator_rejected() {
        assertNull(parser.parse("50 0", GpuFormat.KGSL_WINDOWED_PAIR))
    }

    @Test
    fun testKgslWindowedPair_overflowValues_rejected() {
        assertNull(parser.parse("999999999999999999999999999999999 100", GpuFormat.KGSL_WINDOWED_PAIR))
        assertNull(parser.parse("100 999999999999999999999999999999999", GpuFormat.KGSL_WINDOWED_PAIR))
    }

    @Test
    fun testPercentageScalar_explicit50Percent() {
        val result = parser.parse("50%", GpuFormat.PERCENTAGE_SCALAR)
        assertNotNull(result)
        assertEquals(50.0, result!!, 0.0001)
    }

    @Test
    fun testPercentageScalar_plainInteger() {
        val result = parser.parse("50", GpuFormat.PERCENTAGE_SCALAR)
        assertNotNull(result)
        assertEquals(50.0, result!!, 0.0001)
    }

    @Test
    fun testPercentageScalar_decimalWithSpaces() {
        val result = parser.parse("  33.5 % \n", GpuFormat.PERCENTAGE_SCALAR)
        assertNotNull(result)
        assertEquals(33.5, result!!, 0.0001)
    }

    @Test
    fun testPercentageScalar_outOfRange_rejected() {
        assertNull(parser.parse("-5", GpuFormat.PERCENTAGE_SCALAR))
        assertNull(parser.parse("-1%", GpuFormat.PERCENTAGE_SCALAR))
        assertNull(parser.parse("101", GpuFormat.PERCENTAGE_SCALAR))
        assertNull(parser.parse("150%", GpuFormat.PERCENTAGE_SCALAR))
    }

    @Test
    fun testPercentageScalar_multipleTokens_rejected() {
        assertNull(parser.parse("50 100", GpuFormat.PERCENTAGE_SCALAR))
        assertNull(parser.parse("gpu 50%", GpuFormat.PERCENTAGE_SCALAR))
    }

    @Test
    fun testUnsupportedFormat_arbitraryText_isUnavailable() {
        assertNull(parser.parse("GPU Mali-G610 MC6 active power state D0", GpuFormat.UNSUPPORTED))
        assertNull(parser.parse("100", GpuFormat.UNSUPPORTED))
        assertNull(parser.parse("gpuinfo: 42", GpuFormat.UNSUPPORTED))
        assertNull(parser.parse("", GpuFormat.UNSUPPORTED))
    }

    @Test
    fun testUnsupportedText_withOtherFormats_isUnavailable() {
        assertNull(parser.parse("malformed raw gpu string", GpuFormat.PERCENTAGE_SCALAR))
        assertNull(parser.parse("malformed raw gpu string", GpuFormat.KGSL_WINDOWED_PAIR))
        assertNull(parser.parse("some text 50 100 extra", GpuFormat.KGSL_WINDOWED_PAIR))
    }

    @Test
    fun testKgslCumulativePair_monotonicDeltasAndResets() {
        val cumulativeParser = GpuSourceParser()

        // First sample seeds counter; cannot compute utilization yet
        assertNull(cumulativeParser.parse("100 200", GpuFormat.KGSL_CUMULATIVE_PAIR))

        // Second sample: deltaBusy = 50, deltaTotal = 100 -> 50.0%
        val second = cumulativeParser.parse("150 300", GpuFormat.KGSL_CUMULATIVE_PAIR)
        assertNotNull(second)
        assertEquals(50.0, second!!, 0.0001)

        // Third sample: deltaBusy = 25, deltaTotal = 100 -> 25.0%
        val third = cumulativeParser.parse("175 400", GpuFormat.KGSL_CUMULATIVE_PAIR)
        assertNotNull(third)
        assertEquals(25.0, third!!, 0.0001)

        // Counter reset: counter drops from 175 to 20 -> reject reset, return null
        assertNull(cumulativeParser.parse("20 50", GpuFormat.KGSL_CUMULATIVE_PAIR))

        // Sample after reset: deltaBusy = 20, deltaTotal = 40 -> 50.0%
        val afterReset = cumulativeParser.parse("40 90", GpuFormat.KGSL_CUMULATIVE_PAIR)
        assertNotNull(afterReset)
        assertEquals(50.0, afterReset!!, 0.0001)

        // Impossible deltaBusy > deltaTotal: busy increases by 60, total increases by 20
        assertNull(cumulativeParser.parse("100 110", GpuFormat.KGSL_CUMULATIVE_PAIR))

        // Zero delta total
        assertNull(cumulativeParser.parse("100 110", GpuFormat.KGSL_CUMULATIVE_PAIR))
    }

    @Test
    fun testTypedGpuSourceModel() {
        val source = GpuSource(
            path = "/sys/class/kgsl/kgsl-3d0/gpu_busy",
            format = GpuFormat.KGSL_WINDOWED_PAIR,
            units = "%",
            scope = GpuScope.DEVICE,
            samplingSemantics = GpuSamplingSemantics.WINDOWED,
            lastSuccessMonotonicNs = 123456L
        )

        assertEquals("/sys/class/kgsl/kgsl-3d0/gpu_busy", source.path)
        assertEquals(GpuFormat.KGSL_WINDOWED_PAIR, source.format)
        assertEquals("%", source.units)
        assertEquals("device", source.scope)
        assertEquals(GpuSamplingSemantics.WINDOWED, source.samplingSemantics)
        assertEquals(123456L, source.lastSuccessMonotonicNs)

        val result = parser.parseUtilization("50 100", source, nowMonotonicNs = 789000L)
        assertNotNull(result)
        assertEquals(50.0, result!!, 0.0001)
        assertEquals(789000L, source.lastSuccessMonotonicNs)
    }

    @Test
    fun testDetectFormatHelper() {
        assertEquals(GpuFormat.PERCENTAGE_SCALAR, GpuSourceParser.detectFormat("50%"))
        assertEquals(GpuFormat.PERCENTAGE_SCALAR, GpuSourceParser.detectFormat("75"))
        assertEquals(GpuFormat.KGSL_WINDOWED_PAIR, GpuSourceParser.detectFormat("43 128"))
        assertEquals(GpuFormat.UNSUPPORTED, GpuSourceParser.detectFormat("gpuinfo text with numbers 1 2 3"))
        assertEquals(GpuFormat.UNSUPPORTED, GpuSourceParser.detectFormat("0 0"))
    }
}
