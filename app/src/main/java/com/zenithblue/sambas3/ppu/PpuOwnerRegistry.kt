package com.zenithblue.sambas3.ppu

/**
 * Single serialized owner for all preparation entry points.
 * An inactive Kotlin Job is not a barrier — ownership survives until retire().
 */
class PpuOwnerRegistry {
    @Volatile
    var current: Owner? = null
        private set

    data class Owner(
        val key: AttemptKey,
        val sessionNumeric: Long,
        val titleId: String,
        val compilePath: String,
        val contract: PreparationContract,
        val createdAtMs: Long,
    ) {
        @Volatile var retired: Boolean = false
        @Volatile var stopFailed: Boolean = false
    }

    @Synchronized
    fun admit(next: Owner): Owner? {
        val cur = current
        if (cur != null && !cur.retired) return null
        current = next
        return next
    }

    @Synchronized
    fun retire(expected: Owner) {
        if (current === expected) {
            expected.retired = true
            current = null
        }
    }

    @Synchronized
    fun peek(): Owner? = current

    fun stillOwner(expected: Owner): Boolean = current === expected && !expected.retired
}

object PpuPreparationOwners {
    val registry = PpuOwnerRegistry()
}
