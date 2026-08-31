package com.zenithblue.sambas3

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmulatorBootRequestTest {
    @Test
    fun freshGameDoesNotRequireSavestateCapability() {
        val request = EmulatorBootRequest(EmulatorBootMode.FreshGame, "/games/title")
        assertNull(request.validationError(hasBootSavestateExport = false))
    }

    @Test
    fun selectedSavestateRejectsMissingCapabilityAndPath() {
        val missingCapability = EmulatorBootRequest(
            EmulatorBootMode.UserSelectedSavestate,
            "/games/title",
            "/saves/title.SAVESTAT.zst",
        )
        assertEquals("boot-savestate-export-missing", missingCapability.validationError(false))

        val missingPath = EmulatorBootRequest(EmulatorBootMode.UserSelectedSavestate, "/games/title")
        assertEquals("missing-savestate-path", missingPath.validationError(true))
    }

    @Test
    fun selectedSavestateRequiresAnExistingNonEmptyFile() {
        val file = File.createTempFile("sambas3-boot-request", ".savestate")
        try {
            val request = EmulatorBootRequest(EmulatorBootMode.UserSelectedSavestate, "/games/title", file.path)
            assertEquals("invalid-savestate-file", request.validationError(true))
            file.writeText("state")
            assertNull(request.validationError(true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun invalidModeIsNotSilentlyTreatedAsFreshGame() {
        val request = EmulatorBootRequest(
            EmulatorBootMode.FreshGame,
            "/games/title",
            parseError = "invalid-boot-mode",
        )
        assertEquals("invalid-boot-mode", request.validationError(true))
    }
}
