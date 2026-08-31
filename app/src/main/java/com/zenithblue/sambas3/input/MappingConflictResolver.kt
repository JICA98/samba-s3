package com.zenithblue.sambas3.input

import android.util.Log

enum class RemapConflictAction {
    REPLACE,
    SWAP,
    CANCEL,
}

data class RemapConflict(
    val target: LogicalControl,
    val keyCode: Int,
    val existing: LogicalControl?,
)

data class RemapResult(
    val bindings: Map<LogicalControl, Int>,
    val applied: Boolean,
)

/**
 * Resolves remap conflicts against the single mapping truth (ControllerProfile.digitalBindings).
 * One physical key → one logical control unless SWAP exchanges two bindings.
 */
object MappingConflictResolver {
    private const val TAG = "S3PADMAP"

    fun findConflict(bindings: Map<LogicalControl, Int>, target: LogicalControl, keyCode: Int): RemapConflict {
        val existing = bindings.entries.firstOrNull { it.value == keyCode && it.key != target }?.key
        return RemapConflict(target, keyCode, existing)
    }

    fun apply(
        bindings: Map<LogicalControl, Int>,
        target: LogicalControl,
        keyCode: Int,
        action: RemapConflictAction,
    ): RemapResult {
        if (target == LogicalControl.PS_HOME_FRONTEND) {
            Log.w(TAG, "remap rejected: PS_HOME_FRONTEND reserved")
            return RemapResult(bindings, applied = false)
        }
        val conflict = findConflict(bindings, target, keyCode)
        return when (action) {
            RemapConflictAction.CANCEL -> RemapResult(bindings, applied = false)
            RemapConflictAction.REPLACE -> {
                val next = bindings.toMutableMap()
                conflict.existing?.let { next.remove(it) }
                next[target] = keyCode
                Log.i(TAG, "remap REPLACE ${target.name} <- $keyCode (cleared ${conflict.existing?.name})")
                RemapResult(next, applied = true)
            }
            RemapConflictAction.SWAP -> {
                val next = bindings.toMutableMap()
                val previousTargetKey = bindings[target]
                if (conflict.existing != null) {
                    if (previousTargetKey != null) next[conflict.existing] = previousTargetKey
                    else next.remove(conflict.existing)
                }
                next[target] = keyCode
                Log.i(TAG, "remap SWAP ${target.name} <-> ${conflict.existing?.name}")
                RemapResult(next, applied = true)
            }
        }
    }
}
