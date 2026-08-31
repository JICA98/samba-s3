package com.zenithblue.sambas3.monitoring

/** Pure presentation-counter math used to lock the FPS/frametime semantics. */
object PresentationMetricsMath {
    fun fpsFromPresentedFrames(presentedDelta: Long, elapsedUs: Long): Float? =
        presentedDelta.takeIf { it > 0L }?.let { it * 1_000_000f / elapsedUs.coerceAtLeast(1L) }

    fun averageFrameTimeMs(frameIntervalsMs: List<Float>): Float? =
        frameIntervalsMs.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}
