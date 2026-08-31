package com.zenithblue.sambas3.monitoring

import org.json.JSONArray
import org.json.JSONObject

/** Defensive parser for the optional native telemetry export. */
object PerformanceMetricsParser {
    private const val MAX_FPS_SAMPLES = 60
    private const val MAX_FRAME_TIME_SAMPLES = 240

    fun parse(raw: String): ParsedMetrics? = runCatching {
        val json = JSONObject(raw)
        val timestampUs = json.optLong("timestampUs", 0L).takeIf { it >= 0L } ?: 0L
        val version = json.optInt("version", -1)
        val frameSourceTrusted = version >= 2 && json.optString("fpsSource", "") == "emu_flip"
        val frameSampleFresh = json.optBoolean("frameSampleFresh", false)
        val fpsSamples = samples(json.optJSONArray("fpsSamples"), timestampUs, MAX_FPS_SAMPLES)
        val frameTimeSamples = samples(json.optJSONArray("frametimeSamples"), timestampUs, MAX_FRAME_TIME_SAMPLES)
        ParsedMetrics(
            version = version,
            timestampUs = timestampUs,
            metrics = EmulatorMetrics(
                timestampNs = timestampUs * 1000L,
                presentedFrameCount = json.optLong("presentedFrameCount", Long.MIN_VALUE).takeIf { it >= 0L },
                vblankCount = json.optLong("vblankCount", Long.MIN_VALUE).takeIf { it >= 0L },
                vblankDelta = json.optLong("vblankDelta", Long.MIN_VALUE).takeIf { it >= 0L },
                fpsSource = json.optString("fpsSource", "").takeIf { it.isNotBlank() },
                fps = finiteNonNegative(json, "fps").takeIf { frameSourceTrusted && frameSampleFresh },
                frameTimeMs = finiteNonNegative(json, "frametimeMs").takeIf { frameSourceTrusted && frameSampleFresh },
                hostCpuPercent = finiteNonNegative(json, "hostCpu"),
                ppuCpuPercent = finiteNonNegative(json, "ppuCpu"),
                spuCpuPercent = finiteNonNegative(json, "spuCpu"),
                rsxCpuPercent = finiteNonNegative(json, "rsxCpu"),
                ppuThreads = nonNegativeInt(json, "ppuThreads"),
                spuThreads = nonNegativeInt(json, "spuThreads"),
                hostThreads = nonNegativeInt(json, "hostThreads"),
                rsxLoadPercent = nonNegativeInt(json, "rsxLoad"),
                fpsSamples = fpsSamples.values,
                frameTimeSamples = frameTimeSamples.values,
                fpsTimedSamples = fpsSamples.timed,
                frameTimeTimedSamples = frameTimeSamples.timed
            )
        )
    }.getOrNull()

    data class ParsedMetrics(val version: Int, val timestampUs: Long, val metrics: EmulatorMetrics)

    private fun finiteNonNegative(json: JSONObject, key: String): Float? =
        json.optDouble(key, Double.NaN).toFloat().takeIf { it.isFinite() && it >= 0f }

    private fun nonNegativeInt(json: JSONObject, key: String): Int? =
        json.optInt(key, Int.MIN_VALUE).takeIf { it >= 0 }

    private data class ParsedSamples(val values: List<Float>, val timed: List<TimedSample>)

    private fun samples(array: JSONArray?, fallbackTimestampUs: Long, maxSamples: Int): ParsedSamples {
        if (array == null) return ParsedSamples(emptyList(), emptyList())
        val values = buildList {
            val start = (array.length() - maxSamples).coerceAtLeast(0)
            for (index in start until array.length()) {
                val value = when (val item = array.opt(index)) {
                    is JSONObject -> item.optDouble("value", Double.NaN).toFloat()
                    else -> (item as? Number)?.toFloat() ?: Float.NaN
                }
                if (value.isFinite() && value >= 0f) add(value)
            }
        }
        val timed = buildList {
            val start = (array.length() - maxSamples).coerceAtLeast(0)
            for (index in start until array.length()) {
                val item = array.opt(index)
                val value = when (item) {
                    is JSONObject -> item.optDouble("value", Double.NaN).toFloat()
                    else -> (item as? Number)?.toFloat() ?: Float.NaN
                }
                val timestampUs = (item as? JSONObject)?.optLong("timestampUs", Long.MIN_VALUE)
                    ?.takeIf { it >= 0L }
                    ?: (fallbackTimestampUs + index)
                if (value.isFinite() && value >= 0f && timestampUs >= 0L) add(TimedSample(timestampUs, value))
            }
        }
        return ParsedSamples(values, timed)
    }
}
