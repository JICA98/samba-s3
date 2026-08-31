package com.zenithblue.sambas3.monitoring

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class GraphScale(val lower: Float, val upper: Float)

/** Pure graph pipeline: native samples are oldest-to-newest and invalid values are discarded. */
object MonitoringGraphMath {
    fun validSamples(values: List<Float>, maxSamples: Int = 60): List<Float> = values
        .filter { it.isFinite() && it >= 0f }
        .takeLast(maxSamples.coerceAtLeast(1))

    /**
     * Stable auto scale.  The lower bound stays at zero and the upper bound is
     * never allowed to chase tiny 59.8/60.1 FPS noise.
     */
    fun autoScale(values: List<Float>, target: Float? = null, fallbackUpper: Float = 60f): GraphScale {
        val valid = validSamples(values)
        val sorted = valid.sorted()
        val p95Index = ceil((sorted.size - 1) * .95).toInt().coerceIn(0, sorted.lastIndex.coerceAtLeast(0))
        val p95 = sorted.getOrNull(p95Index)
        val upper = max(30f, max(target ?: 0f, (p95 ?: fallbackUpper) * 1.10f))
        return GraphScale(0f, if (upper.isFinite() && upper > 0f) ceil(upper) else fallbackUpper)
    }

    /** Values map bottom-to-top with the documented scale upper edge at y=0. */
    fun mapToUnit(values: List<Float>, scale: GraphScale): List<Float> {
        val range = (scale.upper - scale.lower).coerceAtLeast(.001f)
        return validSamples(values).map { ((it - scale.lower) / range).coerceIn(0f, 1f) }
    }

    fun rightAnchoredSlots(sampleCount: Int, capacity: Int): List<Int> {
        if (sampleCount <= 0 || capacity <= 0) return emptyList()
        val count = sampleCount.coerceAtMost(capacity)
        return (capacity - count until capacity).toList()
    }

    fun timestampX(timestampUs: Long, nowUs: Long, historySeconds: Int): Float {
        val window = historySeconds.coerceAtLeast(1) * 1_000_000L
        return ((timestampUs - (nowUs - window)).toFloat() / window).coerceIn(0f, 1f)
    }

    fun fpsScale(values: List<Float>, mode: FpsGraphScale, target: Float = 60f): GraphScale = when (mode) {
        FpsGraphScale.ZeroBased -> autoScale(values, fallbackUpper = target)
        FpsGraphScale.TargetWindow -> {
            val center = if (target >= 50f) 60f else 30f
            GraphScale(if (center >= 50f) 40f else 20f, if (center >= 50f) 65f else 35f)
        }
        FpsGraphScale.StableAuto -> {
            val valid = validSamples(values)
            if (valid.isEmpty()) GraphScale(0f, target) else {
                val low = (valid.minOrNull()!! - 5f).coerceAtLeast(0f).let { (it / 5f).roundToInt() * 5f }
                val high = (valid.maxOrNull()!! + 5f).let { (it / 5f).roundToInt() * 5f }
                GraphScale(low, max(low + 5f, high))
            }
        }
    }

    fun frameTimeScale(values: List<Float>, targetFps: Float = 60f): GraphScale {
        val reference = 1000f / targetFps.coerceAtLeast(1f)
        return GraphScale(0f, if (reference <= 20f) 33.3f else 50f)
    }
}
