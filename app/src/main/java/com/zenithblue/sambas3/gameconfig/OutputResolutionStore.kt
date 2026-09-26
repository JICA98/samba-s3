package com.zenithblue.sambas3.gameconfig

import android.content.Context

/** Per-title presentation buffer height selected in Game Configuration. */
object OutputResolutionStore {
    private const val PREFS = "sambas3_output_resolution"
    private const val KEY_PREFIX = "title."
    private val titleIdPattern = Regex("[A-Z0-9_.-]{3,64}")
    val supportedHeights: Set<Int> = setOf(360, 540, 720, 1080)

    /** Returns null when this title has no explicit output-size selection. */
    fun selectedHeight(context: Context, titleId: String): Int? {
        val key = keyFor(titleId) ?: return null
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(key, 0)
        return value.takeIf(supportedHeights::contains)
    }

    /** Persist a supported height, or pass null to restore size-from-layout behavior. */
    fun setSelectedHeight(context: Context, titleId: String, height: Int?) {
        val key = requireNotNull(keyFor(titleId)) { "Invalid title ID" }
        require(height == null || height in supportedHeights) {
            "Output height must be one of ${supportedHeights.sorted().joinToString()}."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (height == null) remove(key) else putInt(key, height)
        }.apply()
    }

    private fun keyFor(titleId: String): String? = titleId.trim().uppercase()
        .takeIf(titleIdPattern::matches)
        ?.let { KEY_PREFIX + it }
}
