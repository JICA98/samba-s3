package com.zenithblue.sambas3.monitoring

import com.zenithblue.sambas3.RPCSX
import org.json.JSONObject

object PerformanceMetricsBridge {
    fun read(): EmulatorMetrics? {
        val raw = runCatching { RPCSX.instance.getPerfMetricsJson() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val j = JSONObject(raw)
            fun f(key: String) = j.optDouble(key, Double.NaN).toFloat().takeUnless { it.isNaN() || it.isInfinite() }
            fun i(key: String) = j.optInt(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
            EmulatorMetrics(
                timestampNs = j.optLong("timestampUs", 0L) * 1000L,
                fps = f("fps"), frameTimeMs = f("frametimeMs"),
                hostCpuPercent = f("hostCpu"), ppuCpuPercent = f("ppuCpu"),
                spuCpuPercent = f("spuCpu"), rsxCpuPercent = f("rsxCpu"),
                ppuThreads = i("ppuThreads"), spuThreads = i("spuThreads"),
                hostThreads = i("hostThreads"), rsxLoadPercent = i("rsxLoad"),
                fpsSamples = j.optJSONArray("fpsSamples")?.toFloatList().orEmpty(),
                frameTimeSamples = j.optJSONArray("frametimeSamples")?.toFloatList().orEmpty()
            )
        }.getOrNull()
    }

    private fun org.json.JSONArray.toFloatList(): List<Float> = buildList(length()) {
        for (index in 0 until length()) {
            val value = optDouble(index, Double.NaN).toFloat()
            if (!value.isNaN() && !value.isInfinite()) add(value)
        }
    }
}
