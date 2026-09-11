package com.zenithblue.sambas3.ui.games

/**
 * Pure focus-index math for the Configure Game page. Indices refer to the flat
 * list of focusable targets (back button, overflow menu, setting rows, gate retry).
 */
object ConfigFocusNavigator {

    /** Wraps [current] by [delta] within [count]; -1 means nothing focused yet. */
    fun move(current: Int, delta: Int, count: Int): Int {
        if (count <= 0) return -1
        if (current < 0) return if (delta >= 0) 0 else count - 1
        return ((current + delta) % count + count) % count
    }

    /** Wraps a section index by [delta] within [sectionCount]. */
    fun sectionJump(current: Int, delta: Int, sectionCount: Int): Int {
        if (sectionCount <= 0) return 0
        val coerced = current.coerceIn(0, sectionCount - 1)
        return ((coerced + delta) % sectionCount + sectionCount) % sectionCount
    }
}
