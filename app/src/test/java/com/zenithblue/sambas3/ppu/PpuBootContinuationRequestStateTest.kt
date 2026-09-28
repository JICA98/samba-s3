package com.zenithblue.sambas3.ppu

import android.os.Bundle
import com.zenithblue.sambas3.EmulatorBootMode
import com.zenithblue.sambas3.EmulatorBootRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PpuBootContinuationRequestStateTest {
    @Test fun recreationPreservesTheEntireUserSavestateRequest() {
        val original = EmulatorBootRequest(
            mode = EmulatorBootMode.UserSelectedSavestate,
            originalGamePath = "/games/BCUS98111.iso",
            savestatePath = "/states/slot-4.sav",
            slot = 4,
            requestId = 812L,
            safeRetry = true,
            parseError = "preserved-invalid-original-marker",
        )
        val saved = Bundle()

        PpuBootContinuationRequestState.save(saved, original)

        assertEquals(original, PpuBootContinuationRequestState.restore(saved))
    }

    @Test fun absentRequestDoesNotInventFreshGameRequest() {
        assertEquals(null, PpuBootContinuationRequestState.restore(Bundle()))
    }
}
