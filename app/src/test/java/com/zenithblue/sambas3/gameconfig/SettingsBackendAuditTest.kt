package com.zenithblue.sambas3.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackendAuditTest {
    @Test
    fun flatten_and_audit_accept_known_paths_and_types() {
        val audit = SettingsBackendAudit.audit(
            listOf(
                RuntimeSettingNode("Video@@Frame limit", "enum"),
                RuntimeSettingNode("Video@@VSync", "bool"),
                RuntimeSettingNode("Core@@PPU Decoder", "enum")
            )
        )

        assertEquals(3, audit.leaves.size)
        assertEquals("enum", audit.leaves.first { it.path == "Video@@Frame limit" }.type)
        assertFalse(audit.duplicatePaths.isNotEmpty())
        assertTrue(audit.typeMismatches.isEmpty())
        assertTrue(audit.missingKnownPaths.any { it.path == "Video@@Resolution" })
    }

    @Test
    fun type_mismatch_and_unknown_type_are_reported() {
        val audit = SettingsBackendAudit.audit(
            listOf(
                RuntimeSettingNode("Video@@Frame limit", "bool"),
                RuntimeSettingNode("Video@@Desktop only", "string")
            )
        )

        assertEquals(
            "bool",
            audit.typeMismatches.single { it.first.path == "Video@@Frame limit" }.second.type
        )
        assertEquals(listOf("Video@@Desktop only"), audit.unsupportedPaths.map { it.path })
        assertEquals(SettingApplyPhase.UNSUPPORTED, SettingsBackendAudit.phaseFor("@@Video@@Desktop only", "string"))
    }

    @Test
    fun lifecycle_hints_are_explicit_for_global_and_in_game_editors() {
        assertEquals(
            "APPLIES AFTER NEXT GAME BOOT",
            SettingsBackendAudit.applyHint("@@Video@@Frame limit", inGame = false, actualType = "enum")
        )
        assertEquals(
            "APPLIES AFTER THIS GAME RESTART",
            SettingsBackendAudit.applyHint("@@Video@@Resolution", inGame = true, actualType = "enum")
        )
        assertEquals(
            "APPLIES NOW · SAVED TO THIS GAME",
            SettingsBackendAudit.applyHint("@@Audio@@Master Volume", inGame = true, actualType = "int")
        )
    }
}
