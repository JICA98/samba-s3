package com.zenithblue.sambas3.monitoring

import kotlin.math.ceil
import kotlin.math.max

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
}
