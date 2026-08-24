package com.zenithblue.sambas3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdentityTest {

    @Test
    fun installedDirectoryAndSourceIsoShareTitleIdentity() {
        val installed = GameIdentity.key(
            "/files/config/games/BLUS31584",
            "GTA San Andreas"
        )
        val sourceIso = GameIdentity.key(
            "/storage/emulated/0/Download/GTA-San-Andreas-BLUS31584.iso",
            "GTA San Andreas"
        )

        assertEquals("BLUS31584", installed)
        assertEquals(installed, sourceIso)
    }

    @Test
    fun installedDirectoryWinsOverSourceIso() {
        assertTrue(
            GameIdentity.preferPath(
                "/files/config/games/BLUS31584",
                "/storage/emulated/0/Download/GTA-San-Andreas-BLUS31584.iso"
            )
        )
        assertFalse(
            GameIdentity.preferPath(
                "/storage/emulated/0/Download/GTA-San-Andreas-BLUS31584.iso",
                "/files/config/games/BLUS31584"
            )
        )
    }
}
