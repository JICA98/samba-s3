package com.zenithblue.sambas3.ppu

/**
 * Host-testable accounting helper matching the native contract:
 * enqueue ≠ commit; cache hits aggregate by identity; all_complete needs a sealed audit.
 */
class PpuBatchAccounting(
    private val required: Set<String>,
    private val budget: Long,
    private val sealed: Boolean,
) {
    init { require(budget > 0) }

    enum class Outcome { MORE_WORK, ALL_COMPLETE, FAILED, CANCELED }

    data class Report(
        val outcome: Outcome,
        val total: Long,
        val cachedBefore: Long,
        val validatedAfter: Long,
        val newlyCommitted: Long,
        val enqueued: Long,
        val inventorySealed: Boolean,
        val audited: Boolean,
    )

    private val initial = linkedSetOf<String>()
    private val current = linkedSetOf<String>()
    private val claimed = linkedSetOf<String>()
    private val written = linkedSetOf<String>()
    private var seeded = false
    private var started = false
    private var audited = false
    private var canceled = false
    private var failed = false

    fun seed(valid: (String) -> Boolean) {
        check(!started) { "cannot reseed a running batch" }
        initial.clear()
        current.clear()
        required.forEach { key -> if (valid(key)) initial.add(key) }
        current.addAll(initial)
        seeded = true
    }

    fun claim(key: String): Boolean {
        check(seeded) { "seed first" }
        started = true
        if (canceled || failed || key !in required || key in current || key in claimed || claimed.size >= budget) {
            return false
        }
        claimed.add(key)
        audited = false
        return true
    }

    fun committed(key: String, valid: (String) -> Boolean): Boolean {
        check(key in claimed) { "object was not claimed" }
        audited = false
        if (!valid(key)) {
            failed = true
            return false
        }
        current.add(key)
        written.add(key)
        return true
    }

    fun audit(valid: (String) -> Boolean) {
        check(seeded) { "seed first" }
        current.clear()
        required.forEach { key -> if (valid(key)) current.add(key) }
        audited = true
    }

    fun cancel() {
        canceled = true
    }

    fun report(): Report {
        var committedCount = 0L
        for (key in written) {
            if (key in current && key !in initial) committedCount++
        }
        val outcome = when {
            canceled -> Outcome.CANCELED
            failed -> Outcome.FAILED
            sealed && audited && current.size.toLong() == required.size.toLong() -> Outcome.ALL_COMPLETE
            else -> Outcome.MORE_WORK
        }
        return Report(
            outcome = outcome,
            total = required.size.toLong(),
            cachedBefore = initial.size.toLong(),
            validatedAfter = current.size.toLong(),
            newlyCommitted = committedCount,
            enqueued = claimed.size.toLong(),
            inventorySealed = sealed,
            audited = audited,
        )
    }
}
