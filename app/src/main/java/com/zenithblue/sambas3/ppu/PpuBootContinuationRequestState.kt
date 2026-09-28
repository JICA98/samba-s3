package com.zenithblue.sambas3.ppu

import android.os.Bundle
import com.zenithblue.sambas3.EmulatorBootMode
import com.zenithblue.sambas3.EmulatorBootRequest

/** Bundle codec for the original, already-normalized launch request. */
internal object PpuBootContinuationRequestState {
    private const val MODE = "ppuContinuationBootMode"
    private const val PATH = "ppuContinuationBootPath"
    private const val SAVESTATE = "ppuContinuationBootSavestate"
    private const val SLOT = "ppuContinuationBootSlot"
    private const val REQUEST_ID = "ppuContinuationBootRequestId"
    private const val SAFE_RETRY = "ppuContinuationBootSafeRetry"
    private const val PARSE_ERROR = "ppuContinuationBootParseError"

    fun restore(state: Bundle?): EmulatorBootRequest? {
        if (state?.containsKey(MODE) != true) return null
        val mode = runCatching { EmulatorBootMode.valueOf(state.getString(MODE).orEmpty()) }.getOrNull()
            ?: return null
        return EmulatorBootRequest(
            mode = mode,
            originalGamePath = state.getString(PATH).orEmpty(),
            savestatePath = state.getString(SAVESTATE),
            slot = state.getInt(SLOT, -1).takeIf { it >= 0 },
            requestId = state.getLong(REQUEST_ID, -1L).takeIf { it >= 0L },
            safeRetry = state.getBoolean(SAFE_RETRY),
            parseError = state.getString(PARSE_ERROR),
        )
    }

    fun save(state: Bundle, request: EmulatorBootRequest) {
        state.putString(MODE, request.mode.name)
        state.putString(PATH, request.originalGamePath)
        state.putString(SAVESTATE, request.savestatePath)
        state.putInt(SLOT, request.slot ?: -1)
        state.putLong(REQUEST_ID, request.requestId ?: -1L)
        state.putBoolean(SAFE_RETRY, request.safeRetry)
        state.putString(PARSE_ERROR, request.parseError)
    }
}
