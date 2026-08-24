package com.zenithblue.sambas3.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleIdResolverTest {

    @Test
    fun title_shaped_install_dir_segment_is_returned() {
        assertEquals(
            "BLUS30441",
            GameSettingsOverrides.resolveTitleId("/storage/emulated/0/dev_hdd0/game/BLUS30441/")
        )
        assertEquals(
            "NPUB31241",
            GameSettingsOverrides.resolveTitleId("dev_hdd0/game/NPUB31241/")
        )
    }

    @Test
    fun lowercase_segments_are_normalized_to_uppercase() {
        assertEquals(
            "BLUS30441",
            GameSettingsOverrides.resolveTitleId("/dev_hdd0/game/blus30441/")
        )
    }

    @Test
    fun non_matching_segments_are_rejected() {
        assertNull(GameSettingsOverrides.resolveTitleId("/dev_hdd0/game/x/"))
        assertNull(GameSettingsOverrides.resolveTitleId("/dev_hdd0/game/my game!/"))
        assertNull(GameSettingsOverrides.resolveTitleId("/dev_hdd0/game/bc!@$/"))
    }

    @Test
    fun blank_paths_are_rejected() {
        assertNull(GameSettingsOverrides.resolveTitleId(""))
        assertNull(GameSettingsOverrides.resolveTitleId("   "))
    }

    @Test
    fun underscore_dots_and_dashes_are_valid_title_id_characters() {
        assertEquals(
            "BCES-00794_TEST.V2",
            GameSettingsOverrides.resolveTitleId("/game/bces-00794_test.v2")
        )
    }
}
