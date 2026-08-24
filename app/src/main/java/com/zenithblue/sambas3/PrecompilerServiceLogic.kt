package com.zenithblue.sambas3

import android.net.Uri

internal object PrecompilerServiceLogic {
    const val EXTRA_ACTION = "action"
    const val EXTRA_URI = "uri"
    const val EXTRA_BATCH = "batch"

    /**
     * A follow-up startForegroundService from the system (no extras) must not
     * stop a job that already opened a large SAF fd / is running native install.
     */
    fun shouldStopEmptyStart(hasRunningJob: Boolean): Boolean = !hasRunningJob

    fun extractUri(data: Uri?, extraUri: Uri?, extraString: String?): Uri? {
        if (data != null) return data
        if (extraUri != null) return extraUri
        val raw = extraString?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }
}
