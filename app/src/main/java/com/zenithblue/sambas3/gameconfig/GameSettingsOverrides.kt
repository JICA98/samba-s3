package com.zenithblue.sambas3.gameconfig

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import org.json.JSONObject

/**
 * Read/write seam over the tier-map persistence backend: SharedPreferences-backed
 * in production, in-memory fake in JVM tests (no Context construction on the JVM).
 */
interface OverrideTierStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class SharedPreferencesTierStore(private val prefs: SharedPreferences) : OverrideTierStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}

/**
 * Scope-aware facade over RPCS3's canonical per-title custom-config store.
 *
 * Persistent production data lives in the core's
 * `custom_configs/config_<TITLE_ID>.yml` as sparse overrides. The old
 * SharedPreferences tier implementation remains as a pure test seam for the
 * resolver tests and for migration compatibility, but is not consulted by the
 * active settings UI.
 *
 * RPCS3 loads global config followed by this title config at every custom boot,
 * so there is no app-side replay that can leak a title value into config.yml.
 *
 * JVM testability: every operation has an internal [OverrideTierStore]-based core;
 * tests exercise those directly with an in-memory store + fake setter, so no test
 * ever constructs an org.json type or an Android Context.
 */
object GameSettingsOverrides {
    private const val TAG = "GameConfig"

    const val PREFS_FILE = "sambas3_game_overrides"
    const val KEY_GLOBAL = "global"
    const val KEY_BASELINE = "baseline"
    const val KEY_GAME_PREFIX = "game."
    private const val KEY_LEARNED_PREFIX = "learned."

    /** TITLE_ID-shaped install-dir segment, e.g. BLUS30441 (dev_hdd0/game/<TITLE_ID>). */
    private val TITLE_ID_REGEX = Regex("[A-Z0-9_.-]{3,64}")

    /**
     * Curated built-in defaults applied first (before baseline). Kept empty by
     * default so no unverified engine node names ship; tests populate it to prove
     * ladder ordering.
     */
    internal var recommendedDefaults: Map<String, String> = emptyMap()

    /** Prefs seam - production opens the app-private SharedPreferences file. */
    internal var storeFactory: (Context) -> OverrideTierStore = { context ->
        SharedPreferencesTierStore(context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE))
    }

    /** Test-only setter seam for the legacy in-memory resolver tests. */
    internal var settingsSetter: (path: String, value: String) -> Boolean = { path, value ->
        RPCSX.instance.settingsSet(path, value)
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /** Record a committed value into the GLOBAL tier (in-game Global Settings page). */
    @Deprecated("Global values are canonical in RPCS3 config.yml")
    fun recordGlobal(context: Context, path: String, encoded: String) {
        runCatching { RPCSX.instance.settingsSetGlobal(path, encoded) }
    }

    internal fun recordGlobal(store: OverrideTierStore, path: String, encoded: String) {
        if (path.isBlank()) return
        val map = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_GLOBAL) ?: "{}")
        map[path] = encoded
        store.putString(KEY_GLOBAL, SettingsValueCodec.encodeOverrideMap(map))
    }

    /**
     * Record a committed value into the PER-TITLE tier, capturing the previous
     * effective encoded value into the shared BASELINE map on first override.
     */
    fun recordGame(
        context: Context,
        titleId: String,
        path: String,
        encoded: String,
        previousEncoded: String?
    ): Boolean {
        if (titleId.isBlank() || path.isBlank()) return false
        return runCatching {
            val wrote = RPCSX.instance.gameSettingsOverrideSet(titleId, path, encoded)
            val readBack = gameOverrides(context, titleId)[path]
            val matched = readBack == encoded
            Log.i(TAG, "S3CFG write scope=game title=$titleId path=$path requested=$encoded readBack=$readBack matched=$matched")
            wrote && matched
        }.getOrDefault(false)
    }

    internal fun recordGame(
        store: OverrideTierStore,
        titleId: String,
        path: String,
        encoded: String,
        previousEncoded: String?
    ) {
        if (titleId.isBlank() || path.isBlank()) return
        val baseline = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_BASELINE) ?: "{}")
        if (!baseline.containsKey(path)) {
            baseline[path] = previousEncoded ?: ""
        }
        store.putString(KEY_BASELINE, SettingsValueCodec.encodeOverrideMap(baseline))

        val gameKey = KEY_GAME_PREFIX + titleId
        val overrides = SettingsValueCodec.decodeOverrideMap(store.getString(gameKey) ?: "{}")
        overrides[path] = encoded
        store.putString(gameKey, SettingsValueCodec.encodeOverrideMap(overrides))
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    /**
     * Delete one per-title row and immediately re-apply the restored effective
     * value (global tier over baseline over captured default) via the setter.
     * @return true when a row was removed AND the restored value applied live.
     */
    fun clearGameSetting(
        context: Context,
        titleId: String?,
        path: String,
        fallbackEncoded: String
    ): Boolean {
        if (titleId.isNullOrBlank() || path.isBlank()) return false
        return runCatching {
            val cleared = RPCSX.instance.gameSettingsOverrideClear(titleId, path)
            val remains = gameOverrides(context, titleId).containsKey(path)
            Log.i(TAG, "S3CFG clear scope=game title=$titleId path=$path cleared=${cleared && !remains}")
            cleared && !remains
        }.getOrDefault(false)
    }

    internal fun clearGameSetting(
        store: OverrideTierStore,
        titleId: String?,
        path: String,
        fallbackEncoded: String,
        setter: (String, String) -> Boolean = settingsSetter
    ): Boolean {
        if (titleId.isNullOrBlank() || path.isBlank()) return false
        val gameKey = KEY_GAME_PREFIX + titleId
        val overrides = SettingsValueCodec.decodeOverrideMap(store.getString(gameKey) ?: "{}")
        if (!overrides.containsKey(path)) return false

        overrides.remove(path)
        store.putString(gameKey, SettingsValueCodec.encodeOverrideMap(overrides))

        val global = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_GLOBAL) ?: "{}")
        val baseline = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_BASELINE) ?: "{}")
        val restored = global[path] ?: baseline[path] ?: fallbackEncoded
        val applied = try {
            setter(path, restored)
        } catch (e: Exception) {
            Log.w(TAG, "clearGameSetting re-apply failed for $path: ${e.message}")
            false
        }
        if (!applied) Log.i(TAG, "clearGameSetting live re-apply rejected for $path=$restored")
        return applied
    }

    /** Wipe every per-title row for [titleId] (other titles untouched). */
    fun clearGame(context: Context, titleId: String): Boolean {
        if (titleId.isBlank()) return false
        return runCatching {
            val cleared = RPCSX.instance.gameSettingsOverridesClear(titleId)
            val remains = gameOverrides(context, titleId).isNotEmpty()
            cleared && !remains
        }.getOrDefault(false)
    }

    internal fun clearGame(store: OverrideTierStore, titleId: String) {
        if (titleId.isBlank()) return
        store.putString(KEY_GAME_PREFIX + titleId, null)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    fun gameOverrides(context: Context, titleId: String): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(RPCSX.instance.gameSettingsOverridesGet(titleId))
            buildMap {
                json.keys().forEach { path ->
                    put(path, json.getString(path))
                }
            }
        }.getOrDefault(emptyMap())
    }

    internal fun gameOverrides(store: OverrideTierStore, titleId: String): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        return SettingsValueCodec.decodeOverrideMap(
            store.getString(KEY_GAME_PREFIX + titleId) ?: "{}"
        )
    }

    /** Compatibility read for old callers; global values are never app-cached now. */
    fun resolvedGlobalValues(context: Context): Map<String, String> =
        emptyMap()

    internal fun resolvedGlobalValues(store: OverrideTierStore): Map<String, String> {
        val baseline = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_BASELINE) ?: "{}")
        val global = SettingsValueCodec.decodeOverrideMap(store.getString(KEY_GLOBAL) ?: "{}")
        return baseline + global
    }

    // ── Boot replay ──────────────────────────────────────────────────────────

    /**
     * Full ordered replay: recommended defaults -> baseline -> global -> per-title.
     * Call PRE-BOOT while Emu.IsStopped() so restart-required nodes accept their
     * values. Rejections are logged, never thrown.
     */
    @Deprecated("RPCS3 loads custom_configs/config_<TITLE_ID>.yml during custom boot")
    fun applyForGame(
        context: Context,
        titleIdOrNull: String?,
        setter: (path: String, value: String) -> Boolean = settingsSetter
    ) = Unit

    internal fun applyForGame(
        store: OverrideTierStore,
        titleIdOrNull: String?,
        setter: (path: String, value: String) -> Boolean
    ) {
        val ladder = ladderSequence(store, titleIdOrNull)
        applyLadder(ladder, setter)
    }

    /**
     * Replay ONLY the per-title tier. Used exclusively by post-boot learning so no
     * restart-required node is written while Running (defaults/baseline/global were
     * already applied pre-boot).
     */
    @Deprecated("RPCS3 loads custom_configs/config_<TITLE_ID>.yml during custom boot")
    fun applyTitleTier(
        context: Context,
        titleId: String?,
        setter: (path: String, value: String) -> Boolean = settingsSetter
    ) = Unit

    internal fun applyTitleTier(
        store: OverrideTierStore,
        titleId: String?,
        setter: (path: String, value: String) -> Boolean
    ) {
        if (titleId.isNullOrBlank()) return
        val title = SettingsValueCodec.decodeOverrideMap(
            store.getString(KEY_GAME_PREFIX + titleId) ?: "{}"
        )
        applyLadder(title.map { it.key to it.value }.asSequence(), setter)
    }

    private fun ladderSequence(store: OverrideTierStore, titleIdOrNull: String?): Sequence<Pair<String, String>> =
        sequence {
            yieldAll(recommendedDefaults.entries.map { it.key to it.value })
            yieldAll(
                SettingsValueCodec.decodeOverrideMap(store.getString(KEY_BASELINE) ?: "{}")
                    .map { it.key to it.value }
            )
            yieldAll(
                SettingsValueCodec.decodeOverrideMap(store.getString(KEY_GLOBAL) ?: "{}")
                    .map { it.key to it.value }
            )
            if (!titleIdOrNull.isNullOrBlank()) {
                yieldAll(
                    SettingsValueCodec.decodeOverrideMap(
                        store.getString(KEY_GAME_PREFIX + titleIdOrNull) ?: "{}"
                    ).map { it.key to it.value }
                )
            }
        }

    private fun applyLadder(
        entries: Sequence<Pair<String, String>>,
        setter: (String, String) -> Boolean
    ) {
        for ((path, value) in entries) {
            val accepted = try {
                setter(path, value)
            } catch (e: Exception) {
                Log.w(TAG, "Replay threw for $path=$value: ${e.message}")
                false
            }
            if (!accepted) Log.i(TAG, "Replay rejected: $path=$value")
        }
    }

    // ── Title-id resolution & learning ───────────────────────────────────────

    /**
     * Resolve the per-title key for [gamePath]. Returns the last path segment when
     * it is TITLE_ID-shaped (install dirs are dev_hdd0/game/<TITLE_ID>); lowercase
     * input is normalized to uppercase. Non-shaped segments fall back to the
     * persisted path->titleId learning map (needs [context]; null skips lookup and
     * behaves exactly like an empty learning map).
     */
    fun resolveTitleId(gamePath: String, context: Context? = null): String? {
        val shaped = shapedTitleSegment(gamePath)
        if (shaped != null) return shaped
        if (context != null) {
            val learned = learnedTitleId(context, gamePath)
            if (!learned.isNullOrBlank()) return learned
        }
        return null
    }

    internal fun resolveTitleId(gamePath: String, learnedLookup: ((String) -> String?)?): String? {
        val shaped = shapedTitleSegment(gamePath)
        if (shaped != null) return shaped
        return learnedLookup?.invoke(gamePath)?.takeIf { it.isNotBlank() }
    }

    /** Persist a path->titleId entry filled post-boot from the engine's getTitleId. */
    fun learnTitleId(context: Context, gamePath: String, titleId: String) =
        learnTitleId(storeFactory(context), gamePath, titleId)

    internal fun learnTitleId(store: OverrideTierStore, gamePath: String, titleId: String) {
        if (gamePath.isBlank() || titleId.isBlank()) return
        store.putString(KEY_LEARNED_PREFIX + gamePath, titleId)
    }

    fun learnedTitleId(context: Context, gamePath: String): String? =
        storeFactory(context).getString(KEY_LEARNED_PREFIX + gamePath)

    internal fun learnedTitleId(store: OverrideTierStore, gamePath: String): String? =
        store.getString(KEY_LEARNED_PREFIX + gamePath)

    private fun shapedTitleSegment(gamePath: String): String? {
        if (gamePath.isBlank()) return null
        val segment = gamePath.trimEnd('/').substringAfterLast('/').trim().uppercase()
        return if (TITLE_ID_REGEX.matches(segment)) segment else null
    }
}
