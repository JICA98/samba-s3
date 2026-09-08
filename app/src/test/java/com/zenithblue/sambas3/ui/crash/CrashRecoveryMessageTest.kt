package com.zenithblue.sambas3.ui.crash

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashRecoveryMessageTest {
    @Test
    fun invalidFileOrFolderExplainsDiscReconnect() {
        assertEquals(
            "The game disc could not be reconnected for this save. Reconnect the ISO and retry.",
            loadFailureMessage("boot-result-InvalidFileOrFolder"),
        )
    }

    @Test
    fun unknownFailureDoesNotExposeRawCoreReason() {
        assertEquals(
            "SambaS3 couldn't restore this save. Retry it, or start the game fresh.",
            loadFailureMessage("boot-result-GenericError"),
        )
    }
}
