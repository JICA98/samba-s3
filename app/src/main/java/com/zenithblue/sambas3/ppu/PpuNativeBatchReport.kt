package com.zenithblue.sambas3.ppu

data class PpuNativeBatchReport(
    val status: String,
    val totalModules: Int,
    val cachedBefore: Int,
    val cachedAfter: Int,
    val compiledThisBatch: Int,
    val enqueued: Int,
    val remainingUncached: Int,
    val inventorySealed: Boolean,
    val audited: Boolean,
    val message: String,
) {
    val provesCompletion: Boolean
        get() = status == "all_complete" &&
            inventorySealed &&
            audited &&
            remainingUncached == 0 &&
            cachedAfter == totalModules &&
            compiledThisBatch <= enqueued

    val outcome: PpuBatchOutcome
        get() = when (status) {
            "all_complete" -> PpuBatchOutcome.ALL_COMPLETE
            "more_work" -> PpuBatchOutcome.MORE_WORK
            "canceled" -> PpuBatchOutcome.CANCELED
            else -> PpuBatchOutcome.FAILED
        }

    companion object {
        fun parse(raw: String?): PpuNativeBatchReport {
            val text = raw ?: "{}"
            fun intField(name: String, default: Int = 0): Int =
                Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: default
            fun strField(name: String, default: String): String =
                Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: default
            fun boolField(name: String, default: Boolean): Boolean =
                Regex("\"$name\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: default
            val status = strField("status", "failed").ifBlank { "failed" }
            val compiled = intField("compiledThisBatch").coerceAtLeast(0)
            val remaining = intField("remainingUncached", if (status == "more_work") 1 else 0).coerceAtLeast(0)
            // Old/malformed reports must never gain a completion proof by default.
            val sealed = boolField("inventorySealed", false)
            val audited = boolField("audited", false)
            return PpuNativeBatchReport(
                status = status,
                totalModules = intField("totalModules").coerceAtLeast(0),
                cachedBefore = intField("cachedBefore").coerceAtLeast(0),
                cachedAfter = intField("cachedAfter").coerceAtLeast(0),
                compiledThisBatch = compiled,
                enqueued = intField("enqueued", compiled).coerceAtLeast(0),
                remainingUncached = remaining,
                inventorySealed = sealed,
                audited = audited,
                message = strField("message", status),
            )
        }
    }
}

object PpuWorkerControlPolicy {
    const val STARTUP_TIMEOUT_MS = 10_000L
    const val CANCEL_GRACE_MS = 2_000L
    const val EXIT_GRACE_MS = 5_000L
    const val BIND_RETRY_LIMIT = 3
    const val ZERO_PROGRESS_BATCH_LIMIT = 3
}
