package com.zenithblue.sambas3.monitoring

/**
 * Merges the runtime core export with the surface-measured fallback.
 *
 * The core's emu_flip counters can legitimately report presented=0 (and no
 * frame samples) while frames are visibly presenting. The native
 * ANativeWindow queueBuffer hook measures those presents directly with its
 * own 2s freshness gate. This merger only fills frame-timing fields that the
 * core left empty — core CPU/thread/RSX fields always win — so no value is
 * ever invented, only a second real measurement source is used.
 */
object PerformanceMetricsMerger {
    data class MergedMetrics(val metrics: EmulatorMetrics, val frameSourceFallback: Boolean)

    fun merge(core: EmulatorMetrics, fallback: EmulatorMetrics): MergedMetrics {
        val useFallbackFrames = (core.fps == null || core.fps <= 0f) && fallback.fps != null && fallback.fps > 0f
        val useFallbackFrameTime = (core.frameTimeMs == null || core.frameTimeMs <= 0f) &&
            fallback.frameTimeMs != null && fallback.frameTimeMs > 0f
        val useFallbackFpsSamples = core.fpsSamples.isEmpty() && fallback.fpsSamples.isNotEmpty()
        val useFallbackFrameSamples = core.frameTimeSamples.isEmpty() && fallback.frameTimeSamples.isNotEmpty()
        val useFallbackTimedFps = core.fpsTimedSamples.isEmpty() && fallback.fpsTimedSamples.isNotEmpty()
        val useFallbackTimedFrame = core.frameTimeTimedSamples.isEmpty() && fallback.frameTimeTimedSamples.isNotEmpty()
        val corePresented = core.presentedFrameCount ?: 0L
        val fallbackPresented = fallback.presentedFrameCount ?: 0L
        // Presented count alone is not displayed; only adopt it alongside real
        // frame data so a stale fallback can never leak anything in.
        val useFallbackPresented = (useFallbackFrames || useFallbackFpsSamples || useFallbackTimedFps) &&
            corePresented <= 0L && fallbackPresented > 0L
        val usedFallback = useFallbackFrames || useFallbackFrameTime || useFallbackFpsSamples ||
            useFallbackFrameSamples || useFallbackTimedFps || useFallbackTimedFrame || useFallbackPresented
        if (!usedFallback) return MergedMetrics(core, false)
        return MergedMetrics(
            core.copy(
                fps = if (useFallbackFrames) fallback.fps else core.fps,
                frameTimeMs = if (useFallbackFrameTime) fallback.frameTimeMs else core.frameTimeMs,
                fpsSamples = if (useFallbackFpsSamples) fallback.fpsSamples else core.fpsSamples,
                frameTimeSamples = if (useFallbackFrameSamples) fallback.frameTimeSamples else core.frameTimeSamples,
                fpsTimedSamples = if (useFallbackTimedFps) fallback.fpsTimedSamples else core.fpsTimedSamples,
                frameTimeTimedSamples = if (useFallbackTimedFrame) fallback.frameTimeTimedSamples else core.frameTimeTimedSamples,
                presentedFrameCount = if (useFallbackPresented) fallback.presentedFrameCount else core.presentedFrameCount,
                fpsSource = if (usedFallback) fallback.fpsSource ?: core.fpsSource else core.fpsSource,
                timestampNs = if (core.timestampNs > 0L) core.timestampNs else fallback.timestampNs,
            ),
            true,
        )
    }
}
