package com.zenithblue.sambas3.monitoring

data class TimedSample(val timestampUs: Long, val value: Float)

/** Single owner for the short, timestamped graph window shown by Compose. */
class MonitoringHistory {
    private val fps = ArrayDeque<TimedSample>()
    private val frameTime = ArrayDeque<TimedSample>()
    private var generation: Long? = null

    fun append(metrics: EmulatorMetrics, historySeconds: Int, enabled: Set<MonitoringMetric>, sessionGeneration: Long = 0L) {
        if (generation != null && generation != sessionGeneration) clear()
        generation = sessionGeneration
        val timestampUs = (metrics.timestampNs / 1_000L).takeIf { it > 0L } ?: return
        val windowStart = timestampUs - historySeconds.coerceIn(5, 30) * 1_000_000L
        if (MonitoringMetric.Fps in enabled) {
            if (metrics.fpsTimedSamples.isNotEmpty()) {
                appendNewSamples(fps, metrics.fpsTimedSamples, windowStart)
            } else {
                metrics.fps?.let { appendNewSamples(fps, listOf(TimedSample(timestampUs, it)), windowStart) }
            }
        }
        if (MonitoringMetric.FrameTime in enabled) {
            if (metrics.frameTimeTimedSamples.isNotEmpty()) {
                appendNewSamples(frameTime, metrics.frameTimeTimedSamples, windowStart)
            } else {
                metrics.frameTimeMs?.let { appendNewSamples(frameTime, listOf(TimedSample(timestampUs, it)), windowStart) }
            }
        }
    }

    fun fps(): List<TimedSample> = fps.toList()
    fun frameTime(): List<TimedSample> = frameTime.toList()
    fun clear() { fps.clear(); frameTime.clear(); generation = null }

    private fun appendNewSamples(target: ArrayDeque<TimedSample>, samples: List<TimedSample>, windowStart: Long) {
        val lastTimestamp = target.lastOrNull()?.timestampUs ?: Long.MIN_VALUE
        samples.asSequence()
            .filter { it.timestampUs > lastTimestamp }
            .sortedBy { it.timestampUs }
            .forEach { target.addLast(it) }
        while (target.firstOrNull()?.timestampUs?.let { it < windowStart } == true) target.removeFirst()
    }
}
