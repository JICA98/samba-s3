package com.zenithblue.sambas3.monitoring

import org.json.JSONArray
import org.json.JSONObject

/** Defensive parser for the optional native telemetry export. */
object PerformanceMetricsParser {
    private const val MAX_SAMPLES = 60

    fun parse(raw: String): ParsedMetrics? = runCatching {
        val json = JSONObject(raw)
        ParsedMetrics(
            version = json.optInt("version", -1),
            timestampUs = json.optLong("timestampUs", 0L).takeIf { it >= 0L } ?: 0L,
            metrics = EmulatorMetrics(
                timestampNs = (json.optLong("timestampUs", 0L).takeIf { it >= 0L } ?: 0L) * 1000L,
                fps = finiteNonNegative(json, "fps"),
                frameTimeMs = finiteNonNegative(json, "frametimeMs"),
                hostCpuPercent = finiteNonNegative(json, "hostCpu"),
                ppuCpuPercent = finiteNonNegative(json, "ppuCpu"),
                spuCpuPercent = finiteNonNegative(json, "spuCpu"),
                rsxCpuPercent = finiteNonNegative(json, "rsxCpu"),
                ppuThreads = nonNegativeInt(json, "ppuThreads"),
                spuThreads = nonNegativeInt(json, "spuThreads"),
                hostThreads = nonNegativeInt(json, "hostThreads"),
                rsxLoadPercent = nonNegativeInt(json, "rsxLoad"),
                fpsSamples = samples(json.optJSONArray("fpsSamples")),
                frameTimeSamples = samples(json.optJSONArray("frametimeSamples"))
            )
        )
    }.getOrNull()

    data class ParsedMetrics(val version: Int, val timestampUs: Long, val metrics: EmulatorMetrics)

    private fun finiteNonNegative(json: JSONObject, key: String): Float? =
        json.optDouble(key, Double.NaN).toFloat().takeIf { it.isFinite() && it >= 0f }

    private fun nonNegativeInt(json: JSONObject, key: String): Int? =
        json.optInt(key, Int.MIN_VALUE).takeIf { it >= 0 }

    private fun samples(array: JSONArray?): List<Float> = buildList {
        if (array == null) return@buildList
        val start = (array.length() - MAX_SAMPLES).coerceAtLeast(0)
        for (index in start until array.length()) {
            val value = array.optDouble(index, Double.NaN).toFloat()
            if (value.isFinite() && value >= 0f) add(value)
        }
    }
}
