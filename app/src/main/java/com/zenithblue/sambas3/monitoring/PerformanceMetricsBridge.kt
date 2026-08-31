package com.zenithblue.sambas3.monitoring

import com.zenithblue.sambas3.RPCSX
import android.os.SystemClock
object PerformanceMetricsBridge : MonitoringPerfSource {
    private var runtimeGateLogged = false
    private var populatedExportLogged = false
    private var lastJsonCrossCheckMs = 0L
    private var lastUiCrossCheckMs = 0L

    override fun setEnabled(enabled: Boolean, intervalMs: Long) {
        if (!enabled) {
            runtimeGateLogged = false
            populatedExportLogged = false
            lastJsonCrossCheckMs = 0L
            lastUiCrossCheckMs = 0L
        }
        runCatching { RPCSX.instance.setPerfMetricsEnabled(enabled, intervalMs.coerceIn(250L, 1000L).toInt()) }
    }

    override fun read(): EmulatorMetrics? {
        val raw = runCatching { RPCSX.instance.getPerfMetricsJson() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return PerformanceMetricsParser.parse(raw)?.let { parsed ->
            parsed.metrics.also { metrics ->
                if (!runtimeGateLogged) {
                    runtimeGateLogged = true
                    android.util.Log.i(
                        "S3PERF",
                        "export=1 payload_length=${raw.length} version=${parsed.version} " +
                            "fps=${metrics.fps ?: "null"} frametime_ms=${metrics.frameTimeMs ?: "null"} " +
                            "fps_samples=${metrics.fpsSamples.size} frametime_samples=${metrics.frameTimeSamples.size} " +
                            "ppu=${metrics.ppuCpuPercent ?: "null"} spu=${metrics.spuCpuPercent ?: "null"} " +
                            "rsx=${metrics.rsxCpuPercent ?: "null"} rsx_load=${metrics.rsxLoadPercent ?: "null"}"
                    )
                }
                if (!populatedExportLogged &&
                    (metrics.fpsSamples.isNotEmpty() || metrics.frameTimeSamples.isNotEmpty())
                ) {
                    populatedExportLogged = true
                    android.util.Log.i(
                        "S3PERF",
                        "export-ready=1 payload_length=${raw.length} version=${parsed.version} " +
                            "fps_samples=${metrics.fpsSamples.size} frametime_samples=${metrics.frameTimeSamples.size}"
                    )
                }
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastJsonCrossCheckMs >= 1_000L && metrics.fps != null && metrics.frameTimeMs != null) {
                    lastJsonCrossCheckMs = nowMs
                    android.util.Log.d(
                        "S3PERF",
                        "crosscheck json_fps=${metrics.fps} json_frametime_ms=${metrics.frameTimeMs}"
                    )
                }
            }
        }
    }

    override fun logUiSnapshot(metrics: EmulatorMetrics) {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastUiCrossCheckMs < 1_000L || metrics.fps == null || metrics.frameTimeMs == null) return
        lastUiCrossCheckMs = nowMs
        android.util.Log.d(
            "S3PERF",
            "crosscheck ui_snapshot_fps=${metrics.fps} ui_snapshot_frametime_ms=${metrics.frameTimeMs}"
        )
    }
}
