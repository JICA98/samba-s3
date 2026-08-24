package com.zenithblue.sambas3.gameconfig

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameSettingsOverridesTest {

    private class InMemoryStore : OverrideTierStore {
        val data = LinkedHashMap<String, String?>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
    }

    private lateinit var store: InMemoryStore
    private val appliedCalls = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        store = InMemoryStore()
        GameSettingsOverrides.recommendedDefaults = linkedMapOf(
            DEFAULT_PATH to "\"built-in\""
        )
        appliedCalls.clear()
    }

    @After
    fun tearDown() {
        GameSettingsOverrides.recommendedDefaults = emptyMap()
    }

    private fun recordingSetter(): (String, String) -> Boolean = { path, value ->
        appliedCalls.add(path to value); true
    }

    // ── Recording ────────────────────────────────────────────────────────────

    @Test
    fun record_game_captures_previous_value_into_baseline_once() {
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "\"Override\"", "\"Global\"")
        val baseline =
            SettingsValueCodec.decodeOverrideMap(store.data[GameSettingsOverrides.KEY_BASELINE] ?: "{}")
        assertEquals("\"Global\"", baseline[PATH_TITLE])

        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "\"Override2\"", "\"Ignored\"")
        val baselineAfter =
            SettingsValueCodec.decodeOverrideMap(store.data[GameSettingsOverrides.KEY_BASELINE] ?: "{}")
        assertEquals("\"Global\"", baselineAfter[PATH_TITLE])
    }

    @Test
    fun record_global_and_overrides_are_readable_back() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "7")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "true", "false")

        // Resolved globals = baseline (captured pre-override value) under global tier.
        assertEquals(
            linkedMapOf(PATH_TITLE to "false", PATH_GLOBAL to "7"),
            GameSettingsOverrides.resolvedGlobalValues(store)
        )
        assertEquals(
            mapOf(PATH_TITLE to "true"),
            GameSettingsOverrides.gameOverrides(store, TITLE_ID)
        )
    }

    @Test
    fun clear_game_wipes_only_that_title() {
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "1", null)
        GameSettingsOverrides.recordGame(store, OTHER_TITLE, PATH_TITLE, "2", null)

        GameSettingsOverrides.clearGame(store, TITLE_ID)

        assertTrue(GameSettingsOverrides.gameOverrides(store, TITLE_ID).isEmpty())
        assertEquals(
            mapOf(PATH_TITLE to "2"),
            GameSettingsOverrides.gameOverrides(store, OTHER_TITLE)
        )
    }

    @Test
    fun clear_game_setting_removes_row_and_reapplies_restored_value_live() {
        GameSettingsOverrides.recordGlobal(store, PATH_TITLE, "\"GlobalVal\"")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "\"Mine\"", "\"GlobalVal\"")
        appliedCalls.clear()

        val restored = GameSettingsOverrides.clearGameSetting(
            store, TITLE_ID, PATH_TITLE,
            fallbackEncoded = "\"EngineDefault\"",
            setter = recordingSetter()
        )

        assertTrue(restored)
        assertFalse(GameSettingsOverrides.gameOverrides(store, TITLE_ID).containsKey(PATH_TITLE))
        assertEquals(listOf(PATH_TITLE to "\"GlobalVal\""), appliedCalls.toList())
    }

    @Test
    fun clear_game_setting_prefers_baseline_over_fallback_and_ignores_unknown_rows() {
        // No global tier entry: the value captured into baseline at first record wins.
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "9", "5")
        appliedCalls.clear()

        GameSettingsOverrides.clearGameSetting(
            store, TITLE_ID, PATH_TITLE, fallbackEncoded = "0", setter = recordingSetter()
        )

        assertEquals(listOf(PATH_TITLE to "5"), appliedCalls.toList())

        // Unknown row: nothing removed, nothing applied.
        appliedCalls.clear()
        assertFalse(
            GameSettingsOverrides.clearGameSetting(
                store, TITLE_ID, "Never@@Recorded", "42", setter = recordingSetter()
            )
        )
        assertTrue(appliedCalls.isEmpty())

        // Title without any overrides: no-op.
        assertFalse(
            GameSettingsOverrides.clearGameSetting(
                store, "UNKNOWN", PATH_TITLE, "0", setter = recordingSetter()
            )
        )
        assertTrue(appliedCalls.isEmpty())
    }

    // ── Boot replay ladder (defaults -> baseline -> global -> title) ─────────

    @Test
    fun apply_for_game_replays_full_ladder_in_exact_order() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "30")
        // Recording for another title seeds the shared BASELINE tier only.
        GameSettingsOverrides.recordGame(store, OTHER_TITLE, PATH_BASELINE, "2", "1")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "60", "55")
        appliedCalls.clear()

        GameSettingsOverrides.applyForGame(store, TITLE_ID, recordingSetter())

        assertEquals(
            listOf(
                DEFAULT_PATH to "\"built-in\"",
                PATH_BASELINE to "1",
                PATH_TITLE to "55",
                PATH_GLOBAL to "30",
                PATH_TITLE to "60"
            ),
            appliedCalls.toList()
        )
    }

    @Test
    fun apply_for_game_with_null_title_applies_everything_but_title_tier() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "30")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "60", "55")
        appliedCalls.clear()

        GameSettingsOverrides.applyForGame(store, titleIdOrNull = null, setter = recordingSetter())

        // PATH_TITLE is shared with the baseline tier by construction; excluding the
        // TITLE tier means its override VALUE (60) never appears — only baseline 55.
        val titlePathCalls = appliedCalls.filter { it.first == PATH_TITLE }
        assertTrue(titlePathCalls.isNotEmpty())
        assertTrue(titlePathCalls.all { it.second == "55" })
        assertTrue(appliedCalls.none { it.first == DEFAULT_PATH && it.second != "\"built-in\"" })
    }

    @Test
    fun apply_title_tier_emits_only_title_paths() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "30")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_TITLE, "60", "55")
        appliedCalls.clear()

        GameSettingsOverrides.applyTitleTier(store, TITLE_ID, recordingSetter())

        assertEquals(listOf(PATH_TITLE to "60"), appliedCalls.toList())
    }

    @Test
    fun apply_title_tier_ignores_blank_titles() {
        GameSettingsOverrides.applyTitleTier(store, null, recordingSetter())
        GameSettingsOverrides.applyTitleTier(store, "", recordingSetter())
        assertTrue(appliedCalls.isEmpty())
    }

    // ── Learning map ─────────────────────────────────────────────────────────

    @Test
    fun learn_title_id_persists_path_mapping_and_lookup_reads_it_back() {
        GameSettingsOverrides.learnTitleId(store, "/odd/My Favorite Game/", TITLE_ID)
        assertEquals(
            TITLE_ID,
            GameSettingsOverrides.resolveTitleId("/odd/My Favorite Game/") { path ->
                GameSettingsOverrides.learnedTitleId(store, path)
            }
        )
    }

    @Test
    fun empty_learning_map_falls_back_to_null() {
        assertNull(
            GameSettingsOverrides.resolveTitleId("/some/odd/Game Dir 1/") { _ -> null }
        )
        assertNull(GameSettingsOverrides.resolveTitleId("/x/y/ab", learnedLookup = null))
    }

    // ── resolveTitleId segment heuristic lives in TitleIdResolverTest ────────

    private companion object {
        const val TITLE_ID = "BLUS30441"
        const val OTHER_TITLE = "BLES00001"
        const val DEFAULT_PATH = "Default@@Tier"
        const val PATH_TITLE = "Video@@Shader Mode"
        const val PATH_BASELINE = "Video@@Frame limit"
        const val PATH_GLOBAL = "Audio@@Master Volume"
    }
}
