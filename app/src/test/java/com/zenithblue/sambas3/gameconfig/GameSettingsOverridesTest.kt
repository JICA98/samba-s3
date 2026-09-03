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
    fun changing_global_moves_only_use_global_rows() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "30")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_GLOBAL, "60", "30")
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "45")

        assertEquals("45", GameSettingsOverrides.resolvedGlobalValues(store)[PATH_GLOBAL])
        assertEquals("60", GameSettingsOverrides.gameOverrides(store, TITLE_ID)[PATH_GLOBAL])

        appliedCalls.clear()
        GameSettingsOverrides.applyForGame(store, TITLE_ID, recordingSetter())
        assertTrue(appliedCalls.contains(PATH_GLOBAL to "45"))
        assertTrue(appliedCalls.contains(PATH_GLOBAL to "60"))
    }

    @Test
    fun recording_game_override_never_writes_global_or_another_title() {
        GameSettingsOverrides.recordGlobal(store, PATH_GLOBAL, "30")
        GameSettingsOverrides.recordGame(store, TITLE_ID, PATH_GLOBAL, "60", "30")

        assertEquals("30", GameSettingsOverrides.resolvedGlobalValues(store)[PATH_GLOBAL])
        assertTrue(GameSettingsOverrides.gameOverrides(store, OTHER_TITLE).isEmpty())
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

    @Test
    fun curated_defaults_require_write_color_buffers_for_demons_souls_and_rdr() {
        val demonTitles = listOf("BLUS30443", "BLES00932", "BCAS20071", "BCJS30022", "BCJS70013", "BCAS20096")
        for (title in demonTitles) {
            val defaults = GameSettingsOverrides.curatedDefaultsForTitle(title)
            assertEquals("true", defaults["Video@@Write Color Buffers"])
        }
        val rdrTitles = listOf("BLUS30758", "BLES01294", "BLUS30418", "BLES00680", "BLJM60233", "BLJM60395", "BLAS50404")
        for (title in rdrTitles) {
            val defaults = GameSettingsOverrides.curatedDefaultsForTitle(title)
            assertEquals("true", defaults["Video@@Write Color Buffers"])
            assertEquals("true", defaults["Video@@Read Color Buffers"])
            assertEquals("200", defaults["Video@@Driver Wake-Up Delay"])
            assertEquals("true", defaults["Core@@SPU loop detection"])
            assertEquals("true", defaults["Video@@Relaxed ZCULL Sync"])
            assertEquals("4", defaults["Core@@Max SPURS Threads"])
        }
        assertTrue(GameSettingsOverrides.curatedDefaultsForTitle("BLUS30441").isEmpty())
        assertTrue(GameSettingsOverrides.curatedDefaultsForTitle(null).isEmpty())

        appliedCalls.clear()
        GameSettingsOverrides.applyForGame(store, "BLUS30758", recordingSetter())
        assertTrue(appliedCalls.contains("Video@@Write Color Buffers" to "true"))
        assertTrue(appliedCalls.contains("Video@@Read Color Buffers" to "true"))
        assertTrue(appliedCalls.contains("Video@@Driver Wake-Up Delay" to "200"))
        assertTrue(appliedCalls.contains("Core@@SPU loop detection" to "true"))
        assertTrue(appliedCalls.contains("Video@@Relaxed ZCULL Sync" to "true"))
        assertTrue(appliedCalls.contains("Core@@Max SPURS Threads" to "4"))
    }

    // ── resolveTitleId segment heuristic lives in TitleIdResolverTest ────────

    // ── Compatibility / explicit-user separation (§56) ─────────────────────

    @Test
    fun explicit_user_map_excludes_compat_and_user_value_wins_resolved() {
        val wcb = "Video@@Write Color Buffers"
        // No explicit state: explicit map empty, resolved falls back to compat.
        assertTrue(GameSettingsOverrides.explicitUserOverrides(store, emptyMap(), "BLUS30758").isEmpty())
        assertEquals("true", GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30758")[wcb])

        // Explicit user value overrides compatibility.
        GameSettingsOverrides.recordGame(store, "BLUS30758", wcb, "false", "true")
        assertEquals("false", GameSettingsOverrides.explicitUserOverrides(store, emptyMap(), "BLUS30758")[wcb])
        assertEquals("false", GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30758")[wcb])

        // Native tier wins over the app-owned tier on conflict.
        assertEquals(
            "native",
            GameSettingsOverrides.explicitUserOverrides(store, mapOf(wcb to "native"), "BLUS30758")[wcb]
        )

        // Clear explicit value succeeds and resolved falls back to compat.
        appliedCalls.clear()
        assertTrue(
            GameSettingsOverrides.clearGameSetting(store, "BLUS30758", wcb, "false", recordingSetter())
        )
        assertTrue(GameSettingsOverrides.explicitUserOverrides(store, emptyMap(), "BLUS30758").isEmpty())
        assertEquals("true", GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30758")[wcb])
    }

    // ── Reset on a curated title (§57) ─────────────────────────────────────

    @Test
    fun clear_all_on_curated_title_succeeds_and_keeps_profile_separate() {
        GameSettingsOverrides.clearGame(store, "BLUS30758")
        assertTrue(GameSettingsOverrides.explicitUserOverrides(store, emptyMap(), "BLUS30758").isEmpty())
        assertEquals("true", GameSettingsOverrides.compatibilityDefaultsForTitle("BLUS30758")["Video@@Write Color Buffers"])
        assertEquals("true", GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30758")["Video@@Write Color Buffers"])

        GameSettingsOverrides.clearGame(store, "BLUS30443")
        assertTrue(GameSettingsOverrides.explicitUserOverrides(store, emptyMap(), "BLUS30443").isEmpty())
        assertEquals("true", GameSettingsOverrides.compatibilityDefaultsForTitle("BLUS30443")["Video@@Write Color Buffers"])
    }

    // ── Backend-apply failure must not be masked (§58) ─────────────────────

    @Test
    fun failed_backend_apply_is_not_masked_by_local_mirror() {
        val leaseStore = InMemoryStore()
        val globals = mutableMapOf("Video@@Write Color Buffers" to "false")
        val begin = GameSettingsOverrides.beginScopedLease(
            leaseStore,
            "BLUS30758",
            mapOf("Video@@Write Color Buffers" to "true"),
            readGlobal = { globals[it] },
            writeGlobal = { _, _ -> false },
            nowMs = 1000L,
            sessionId = 42L
        )
        assertFalse(begin.allApplied)
        assertEquals(false, begin.perKeyOk["Video@@Write Color Buffers"])
        // Backend still holds the old value: no fake success.
        assertEquals("false", globals["Video@@Write Color Buffers"])
    }

    // ── Scoped restore + crash recovery (§59) ──────────────────────────────

    @Test
    fun scoped_lease_restores_exact_globals_and_recovers_after_crash() {
        val leaseStore = InMemoryStore()
        val globals = mutableMapOf(
            "Video@@Write Color Buffers" to "false",
            "Video@@Read Color Buffers" to "false",
            "Video@@Driver Wake-Up Delay" to "0"
        )
        val read: (String) -> String? = { globals[it] }
        val write: (String, String) -> Boolean = { p, v -> globals[p] = v; true }
        val resolved = mapOf(
            "Video@@Write Color Buffers" to "true",
            "Video@@Read Color Buffers" to "true",
            "Video@@Driver Wake-Up Delay" to "200"
        )

        val begin = GameSettingsOverrides.beginScopedLease(leaseStore, "BLUS30758", resolved, read, write, nowMs = 1234L, sessionId = 999L)
        assertTrue(begin.allApplied)
        assertEquals("true", globals["Video@@Write Color Buffers"])
        assertEquals("200", globals["Video@@Driver Wake-Up Delay"])

        val end = GameSettingsOverrides.endScopedLease(leaseStore, read, write)
        assertTrue(end.hadLease)
        assertTrue(end.allRestored)
        assertTrue(end.leaseCleared)
        assertEquals("false", globals["Video@@Write Color Buffers"])
        assertEquals("false", globals["Video@@Read Color Buffers"])
        assertEquals("0", globals["Video@@Driver Wake-Up Delay"])

        // Crash path: lease persists across "death", next boot recovers it.
        GameSettingsOverrides.beginScopedLease(leaseStore, "BLUS30758", resolved, read, write, nowMs = 2000L, sessionId = 1000L)
        assertEquals("true", globals["Video@@Write Color Buffers"])
        assertTrue(GameSettingsOverrides.recoverStaleLease(leaseStore, read, write))
        assertEquals("false", globals["Video@@Write Color Buffers"])
        assertNull(GameSettingsOverrides.readLease(leaseStore))
    }

    @Test
    fun lease_skips_keys_absent_before_boot() {
        val leaseStore = InMemoryStore()
        val globals = mutableMapOf<String, String>()
        val begin = GameSettingsOverrides.beginScopedLease(
            leaseStore, "BLUS30758",
            mapOf("Video@@Write Color Buffers" to "true"),
            readGlobal = { globals[it] },
            writeGlobal = { p, v -> globals[p] = v; true },
            nowMs = 1L, sessionId = 2L
        )
        assertTrue(begin.allApplied)
        val end = GameSettingsOverrides.endScopedLease(leaseStore, { globals[it] }, { p, v -> globals[p] = v; true })
        assertTrue(end.allRestored)
        assertTrue(end.leaseCleared)
    }

    // ── Cross-title isolation (§61) ─────────────────────────────────────────

    @Test
    fun cross_title_sequence_does_not_leak() {
        val leaseStore = InMemoryStore()
        val globals = mutableMapOf(
            "Video@@Write Color Buffers" to "false",
            "Video@@Read Color Buffers" to "false",
            "Video@@Driver Wake-Up Delay" to "0"
        )
        val read: (String) -> String? = { globals[it] }
        val write: (String, String) -> Boolean = { p, v -> globals[p] = v; true }

        // RDR boots with its profile, then exits and restores exact globals.
        val rdrResolved = GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30758")
        assertTrue(rdrResolved.isNotEmpty())
        GameSettingsOverrides.beginScopedLease(leaseStore, "BLUS30758", rdrResolved, read, write, nowMs = 1L, sessionId = 1L)
        assertEquals("true", globals["Video@@Write Color Buffers"])
        GameSettingsOverrides.endScopedLease(leaseStore, read, write)
        assertEquals("false", globals["Video@@Write Color Buffers"])
        assertEquals("0", globals["Video@@Driver Wake-Up Delay"])

        // Demon's Souls sees only its own compatibility value.
        val dsResolved = GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30443")
        assertEquals(mapOf("Video@@Write Color Buffers" to "true"), dsResolved)
        GameSettingsOverrides.beginScopedLease(leaseStore, "BLUS30443", dsResolved, read, write, nowMs = 2L, sessionId = 2L)
        assertEquals("true", globals["Video@@Write Color Buffers"])
        assertEquals("false", globals["Video@@Read Color Buffers"])
        GameSettingsOverrides.endScopedLease(leaseStore, read, write)

        // Generic title: nothing applies, globals untouched.
        assertTrue(GameSettingsOverrides.resolvedBootOverrides(store, emptyMap(), "BLUS30441").isEmpty())
        assertEquals("false", globals["Video@@Write Color Buffers"])
        assertEquals("false", globals["Video@@Read Color Buffers"])
        assertEquals("0", globals["Video@@Driver Wake-Up Delay"])
    }

    private companion object {
        const val TITLE_ID = "BLUS30441"
        const val OTHER_TITLE = "BLES00001"
        const val DEFAULT_PATH = "Default@@Tier"
        const val PATH_TITLE = "Video@@Shader Mode"
        const val PATH_BASELINE = "Video@@Frame limit"
        const val PATH_GLOBAL = "Audio@@Master Volume"
    }
}
