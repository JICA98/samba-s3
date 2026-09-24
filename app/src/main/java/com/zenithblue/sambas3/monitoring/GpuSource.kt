package com.zenithblue.sambas3.monitoring

enum class GpuFormat {
    PERCENTAGE_SCALAR,
    KGSL_WINDOWED_PAIR,
    KGSL_CUMULATIVE_PAIR,
    UNSUPPORTED
}

enum class GpuSamplingSemantics {
    WINDOWED,
    CUMULATIVE,
    INSTANTANEOUS,
    UNKNOWN
}

enum class GpuScope {
    DEVICE,
    SYSTEM,
    UNKNOWN;

    override fun toString(): String = name.lowercase()
}

data class GpuSource(
    val path: String,
    val format: GpuFormat,
    val units: String = "%",
    val scope: String = "device",
    val samplingSemantics: GpuSamplingSemantics = when (format) {
        GpuFormat.KGSL_WINDOWED_PAIR -> GpuSamplingSemantics.WINDOWED
        GpuFormat.KGSL_CUMULATIVE_PAIR -> GpuSamplingSemantics.CUMULATIVE
        GpuFormat.PERCENTAGE_SCALAR -> GpuSamplingSemantics.INSTANTANEOUS
        GpuFormat.UNSUPPORTED -> GpuSamplingSemantics.UNKNOWN
    },
    var lastSuccessMonotonicNs: Long = 0L
) {
    constructor(
        path: String,
        format: GpuFormat,
        units: String = "%",
        scope: GpuScope,
        samplingSemantics: GpuSamplingSemantics = when (format) {
            GpuFormat.KGSL_WINDOWED_PAIR -> GpuSamplingSemantics.WINDOWED
            GpuFormat.KGSL_CUMULATIVE_PAIR -> GpuSamplingSemantics.CUMULATIVE
            GpuFormat.PERCENTAGE_SCALAR -> GpuSamplingSemantics.INSTANTANEOUS
            GpuFormat.UNSUPPORTED -> GpuSamplingSemantics.UNKNOWN
        },
        lastSuccessMonotonicNs: Long = 0L
    ) : this(path, format, units, scope.name.lowercase(), samplingSemantics, lastSuccessMonotonicNs)
}

class GpuSourceParser {
    private var lastCumulativeCounters: Pair<Long, Long>? = null

    fun resetCumulativeCounters() {
        lastCumulativeCounters = null
    }

    fun parseUtilization(
        rawText: String,
        source: GpuSource,
        nowMonotonicNs: Long = System.nanoTime()
    ): Double? {
        val result = parseUtilization(rawText, source.format)
        if (result != null) {
            source.lastSuccessMonotonicNs = nowMonotonicNs
        }
        return result
    }

    fun parseUtilization(rawText: String, format: GpuFormat): Double? = when (format) {
        GpuFormat.PERCENTAGE_SCALAR -> parsePercentageScalar(rawText)
        GpuFormat.KGSL_WINDOWED_PAIR -> parseKgslWindowedPair(rawText)
        GpuFormat.KGSL_CUMULATIVE_PAIR -> parseKgslCumulativePair(rawText)
        GpuFormat.UNSUPPORTED -> null
    }

    fun parse(rawText: String, format: GpuFormat): Double? = parseUtilization(rawText, format)

    fun parseKgslCumulativePair(rawText: String): Double? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size != 2) return null
        val busy = parts[0].toLongOrNull() ?: return null
        val total = parts[1].toLongOrNull() ?: return null

        if (busy < 0L || total < 0L) return null

        val previous = lastCumulativeCounters
        lastCumulativeCounters = busy to total

        if (previous == null) {
            // First cumulative observation seeds state; utilization cannot be computed yet
            return null
        }

        val deltaBusy = busy - previous.first
        val deltaTotal = total - previous.second

        // Reject counter reset (negative deltas), zero/negative denominator, or impossible busy > total
        if (deltaBusy < 0L || deltaTotal <= 0L || deltaBusy > deltaTotal) {
            return null
        }

        return (100.0 * deltaBusy) / deltaTotal
    }

    companion object {
        fun parsePercentageScalar(rawText: String): Double? {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null
            val token = if (trimmed.endsWith("%")) {
                trimmed.removeSuffix("%").trim()
            } else {
                trimmed
            }
            if (token.contains(Regex("\\s+"))) return null
            val value = token.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value < 0.0 || value > 100.0) return null
            return value
        }

        fun parseKgslWindowedPair(rawText: String): Double? {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size != 2) return null
            val busy = parts[0].toLongOrNull() ?: return null
            val total = parts[1].toLongOrNull() ?: return null

            // Reject zero denominator, negative/overflow values, impossible busy > total
            if (total <= 0L || busy < 0L || busy > total) return null

            return (100.0 * busy) / total
        }

        fun detectFormat(sampleText: String): GpuFormat {
            val trimmed = sampleText.trim()
            if (trimmed.isEmpty()) return GpuFormat.UNSUPPORTED
            if (trimmed.endsWith("%")) {
                val num = trimmed.removeSuffix("%").trim().toDoubleOrNull()
                if (num != null && num in 0.0..100.0) return GpuFormat.PERCENTAGE_SCALAR
            }
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size == 1) {
                val num = parts[0].toDoubleOrNull()
                if (num != null && num in 0.0..100.0) return GpuFormat.PERCENTAGE_SCALAR
            } else if (parts.size == 2) {
                val b = parts[0].toLongOrNull()
                val t = parts[1].toLongOrNull()
                if (b != null && t != null && b >= 0L && t > 0L && b <= t) {
                    return GpuFormat.KGSL_WINDOWED_PAIR
                }
            }
            return GpuFormat.UNSUPPORTED
        }
    }
}
