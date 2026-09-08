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
 * Scope-aware facade over per-title configuration.
 *
 * Three strictly separated concepts (precedence for boot):
 *   1. Global config — canonical engine config.yml, shared by all titles.
 *   2. Compatibility profile ([compatibilityDefaultsForTitle]) — built-in,
 *      title-scoped correctness defaults. Never user state.
 *   3. Explicit user per-title overrides ([explicitUserOverrides]) — values the
 *      user committed for this title. These win over the profile.
 *
 * The Android core boots with cfg_mode::global, so title values are applied
 * through a crash-safe scoped lease ([beginScopedLeaseForBoot]): affected
 * global keys are snapshotted, resolved title values applied pre-boot, and
 * exact originals restored on clean exit ([endScopedLeaseAfterBoot]).
 * A stale lease from a crashed session is restored before the next title
 * boots ([recoverStaleLease]). [gameOverrides] exposes explicit user state
 * only, so Reset/Clear/"has custom settings" never confuse curated defaults
 * with user data.
 *
 * JVM testability: every operation has an internal [OverrideTierStore]-based
 * core; tests exercise those directly with an in-memory store + fake setter,
 * so no test ever constructs an org.json type or an Android Context.
 */
object GameSettingsOverrides {
    private const val TAG = "GameConfig"

    const val PREFS_FILE = "sambas3_game_overrides"
    const val KEY_GLOBAL = "global"
    const val KEY_BASELINE = "baseline"
    const val KEY_GAME_PREFIX = "game."
    private const val KEY_LEARNED_PREFIX = "learned."
    const val LEASE_PREFS_FILE = "sambas3_game_lease"
    private const val LEASE_ABSENT = "<absent>"

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
     * Curated game-specific defaults that are strictly required for playable rendering.
     * For example, Demon's Souls requires Write Color Buffers: true to avoid rendering a black 3D screen.
     */
    fun curatedDefaultsForTitle(titleId: String?): Map<String, String> {
        if (titleId.isNullOrBlank()) return emptyMap()
        return when (titleId.uppercase()) {
            "BLUS30443", "BLES00932", "BCAS20071", "BCJS30022", "BCJS70013", "BCAS20096" -> mapOf(
                "Video@@Write Color Buffers" to "true"
            )
            // Red Dead Redemption: requires Write Color Buffers & Read Color Buffers for in-game lighting/menus,
            // Driver Wake-Up Delay: 200 to prevent SPU/driver sync deadlock, SPU loop detection: true,
            // Relaxed ZCULL Sync: true for framerate, Max SPURS Threads: 4 to prevent CPU starvation on mobile.
            // Handle RSX Memory Tiling: false — on the tested Adreno/Turnip path enabling tiling reproduces a
            // DMA-fence vk::wait_for_event stall; keep disabled for this title (crash avoidance, not a general
            // claim about tiling emulation on unified-memory GPUs).
            "BLUS30758", "BLES01294", "BLUS30418", "BLES00680", "BLJM60233", "BLJM60395", "BLAS50404" -> mapOf(
                "Video@@Write Color Buffers" to "true",
                "Video@@Read Color Buffers" to "true",
                "Video@@Handle RSX Memory Tiling" to "false",
                "Video@@Driver Wake-Up Delay" to "200",
                "Core@@SPU loop detection" to "true",
                "Video@@Relaxed ZCULL Sync" to "true",
                "Core@@Max SPURS Threads" to "4"
            )
            else -> emptyMap()
        }
    }

    /**
     * Built-in compatibility profile for [titleId]. Alias of
     * [curatedDefaultsForTitle] using the §24 naming: compatibility defaults
     * are product-owned correctness data, never explicit user state.
     */
    fun compatibilityDefaultsForTitle(titleId: String?): Map<String, String> =
        curatedDefaultsForTitle(titleId)

    /**
     * Persist a committed value into the app-owned PER-TITLE tier.
     *
     * The app-owned tier is the authority for "saved" on current cores (the
     * packaged core has no per-title backend symbols, so the native write is
     * best-effort and reported separately). "Applied" is proven only by the
     * scoped pre-boot lease, never by this return value.
     * @return true when the app-owned tier persisted and reads back the value.
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
            val wroteNative = runCatching {
                RPCSX.instance.gameSettingsOverrideSet(titleId, path, encoded)
            }.getOrDefault(false)
            recordGame(storeFactory(context), titleId, path, encoded, previousEncoded)
            val persisted = gameOverrides(storeFactory(context), titleId)[path] == encoded
            Log.i(TAG, "S3CFG save scope=game title=$titleId path=$path requested=$encoded persisted=$persisted native=$wroteNative")
            persisted
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
            val clearedNative = runCatching {
                RPCSX.instance.gameSettingsOverrideClear(titleId, path)
            }.getOrDefault(false)
            val clearedLocal = clearGameSetting(storeFactory(context), titleId, path, fallbackEncoded)
            // Explicit-user state only: curated compatibility defaults must not
            // count as remaining overrides.
            val remains = explicitUserOverrides(context, titleId).containsKey(path)
            Log.i(TAG, "S3CFG clear scope=game title=$titleId path=$path clearedNative=$clearedNative clearedLocal=$clearedLocal remains=$remains")
            !remains
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

    /** Wipe every explicit per-title row for [titleId] (other titles untouched). */
    fun clearGame(context: Context, titleId: String): Boolean {
        if (titleId.isBlank()) return false
        return runCatching {
            runCatching { RPCSX.instance.gameSettingsOverridesClear(titleId) }
            clearGame(storeFactory(context), titleId)
            // Explicit-user state only: the compatibility profile lives
            // separately and must not block reset.
            val remains = explicitUserOverrides(context, titleId).isNotEmpty()
            !remains
        }.getOrDefault(false)
    }

    internal fun clearGame(store: OverrideTierStore, titleId: String) {
        if (titleId.isBlank()) return
        store.putString(KEY_GAME_PREFIX + titleId, null)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Explicit user per-title state ONLY (app-owned tier + native title tier).
     * Built-in compatibility defaults are NOT included: Game Settings screen,
     * Reset, Clear and "has custom settings?" must reflect user data alone.
     */
    fun gameOverrides(context: Context, titleId: String): Map<String, String> =
        explicitUserOverrides(context, titleId)

    /**
     * Explicit user per-title overrides for [titleId]: app-owned tier merged
     * with the native title tier (native wins on conflict). No curated data.
     */
    fun explicitUserOverrides(context: Context, titleId: String): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        val nativeMap = runCatching {
            val json = JSONObject(RPCSX.instance.gameSettingsOverridesGet(titleId))
            buildMap {
                json.keys().forEach { path ->
                    put(path, json.getString(path))
                }
            }
        }.getOrDefault(emptyMap())
        return explicitUserOverrides(storeFactory(context), nativeMap, titleId)
    }

    internal fun explicitUserOverrides(
        store: OverrideTierStore,
        nativeMap: Map<String, String>,
        titleId: String
    ): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        return gameOverrides(store, titleId) + nativeMap
    }

    /**
     * Resolved boot values for [titleId]: compatibility profile first, then
     * explicit user values (user wins). This is the exact map the scoped
     * lease applies to global config pre-boot.
     */
    fun resolvedBootOverrides(context: Context, titleId: String): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        return resolvedBootOverrides(storeFactory(context), readNativeMap(titleId), titleId)
    }

    internal fun resolvedBootOverrides(
        store: OverrideTierStore,
        nativeMap: Map<String, String>,
        titleId: String
    ): Map<String, String> {
        if (titleId.isBlank()) return emptyMap()
        return compatibilityDefaultsForTitle(titleId) + explicitUserOverrides(store, nativeMap, titleId)
    }

    private fun readNativeMap(titleId: String): Map<String, String> = runCatching {
        val json = JSONObject(RPCSX.instance.gameSettingsOverridesGet(titleId))
        buildMap {
            json.keys().forEach { path ->
                put(path, json.getString(path))
            }
        }
    }.getOrDefault(emptyMap())

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

    // ── Scoped global-config lease ─────────────────────────────────────────
    //
    // The Android core boots with cfg_mode::global, so title values are applied
    // by writing global config immediately pre-boot. The lease snapshots the
    // exact affected global values first, so the next boot (or a crash
    // recovery) can restore them and no RDR/DS value leaks into other titles.

    data class ScopedGameSettingsLease(
        val sessionId: Long,
        val titleId: String,
        val originalGlobalValues: Map<String, String>,
        val appliedValues: Map<String, String>,
        val createdMs: Long,
    )

    data class LeaseBeginResult(
        val lease: ScopedGameSettingsLease?,
        val allApplied: Boolean,
        val perKeyOk: Map<String, Boolean>,
    )

    data class LeaseEndResult(
        val hadLease: Boolean,
        val allRestored: Boolean,
        val leaseCleared: Boolean,
        val perKeyOk: Map<String, Boolean>,
    )

    private const val LEASE_KEY_SESSION = "lease.session"
    private const val LEASE_KEY_TITLE = "lease.title"
    private const val LEASE_KEY_CREATED = "lease.created"
    private const val LEASE_KEY_ORIGINALS = "lease.originals"
    private const val LEASE_KEY_APPLIED = "lease.applied"

    internal fun readLease(leaseStore: OverrideTierStore): ScopedGameSettingsLease? {
        val session = leaseStore.getString(LEASE_KEY_SESSION)?.toLongOrNull() ?: return null
        val title = leaseStore.getString(LEASE_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val created = leaseStore.getString(LEASE_KEY_CREATED)?.toLongOrNull() ?: return null
        val originals = SettingsValueCodec.decodeOverrideMap(
            leaseStore.getString(LEASE_KEY_ORIGINALS) ?: return null
        )
        val applied = SettingsValueCodec.decodeOverrideMap(
            leaseStore.getString(LEASE_KEY_APPLIED) ?: return null
        )
        return ScopedGameSettingsLease(session, title, originals, applied, created)
    }

    private fun writeLease(leaseStore: OverrideTierStore, lease: ScopedGameSettingsLease) {
        leaseStore.putString(LEASE_KEY_SESSION, lease.sessionId.toString())
        leaseStore.putString(LEASE_KEY_TITLE, lease.titleId)
        leaseStore.putString(LEASE_KEY_CREATED, lease.createdMs.toString())
        leaseStore.putString(LEASE_KEY_ORIGINALS, SettingsValueCodec.encodeOverrideMap(lease.originalGlobalValues))
        leaseStore.putString(LEASE_KEY_APPLIED, SettingsValueCodec.encodeOverrideMap(lease.appliedValues))
    }

    internal fun clearLease(leaseStore: OverrideTierStore) {
        leaseStore.putString(LEASE_KEY_SESSION, null)
        leaseStore.putString(LEASE_KEY_TITLE, null)
        leaseStore.putString(LEASE_KEY_CREATED, null)
        leaseStore.putString(LEASE_KEY_ORIGINALS, null)
        leaseStore.putString(LEASE_KEY_APPLIED, null)
    }

    /**
     * Snapshot affected globals, persist the lease, then apply [resolved].
     * The lease is verified on disk BEFORE any global is mutated; globals are
     * never touched when persistence fails. A failed per-key apply is reported
     * in [LeaseBeginResult], never masked by the local mirror.
     */
    internal fun beginScopedLease(
        leaseStore: OverrideTierStore,
        titleId: String,
        resolved: Map<String, String>,
        readGlobal: (String) -> String?,
        writeGlobal: (String, String) -> Boolean,
        nowMs: Long = System.currentTimeMillis(),
        sessionId: Long = nowMs,
    ): LeaseBeginResult {
        if (titleId.isBlank() || resolved.isEmpty()) return LeaseBeginResult(null, true, emptyMap())
        val originals = LinkedHashMap<String, String>()
        for (path in resolved.keys) {
            originals[path] = readGlobal(path) ?: LEASE_ABSENT
        }
        val lease = ScopedGameSettingsLease(sessionId, titleId, originals, resolved, nowMs)
        writeLease(leaseStore, lease)
        if (readLease(leaseStore) != lease) {
            Log.w(TAG, "S3GAMECFG boot title=$titleId lease=$sessionId persist=FAIL")
            return LeaseBeginResult(null, false, emptyMap())
        }
        val perKey = LinkedHashMap<String, Boolean>()
        for ((path, value) in resolved) {
            val wrote = try { writeGlobal(path, value) } catch (_: Exception) { false }
            val matched = try { readGlobal(path) == value } catch (_: Exception) { false }
            perKey[path] = wrote && matched
            if (!(wrote && matched)) Log.w(TAG, "S3GAMECFG boot title=$titleId lease=$sessionId applyFAIL path=$path wrote=$wrote matched=$matched")
        }
        val allOk = perKey.values.all { it }
        Log.i(TAG, "S3GAMECFG boot title=$titleId lease=$sessionId keys=${resolved.keys} applied=${perKey.count { it.value }}/${perKey.size} globalBefore=$originals")
        return LeaseBeginResult(lease, allOk, perKey)
    }

    /**
     * Restore the exact globals captured by the active lease and delete it.
     * The lease is deleted only after restoration is verified.
     */
    internal fun endScopedLease(
        leaseStore: OverrideTierStore,
        readGlobal: (String) -> String?,
        writeGlobal: (String, String) -> Boolean,
        reason: String = "exit",
    ): LeaseEndResult {
        val lease = readLease(leaseStore)
            ?: return LeaseEndResult(hadLease = false, allRestored = true, leaseCleared = true, perKeyOk = emptyMap())
        Log.i(TAG, "S3GAMECFG restore-begin reason=$reason title=${lease.titleId} lease=${lease.sessionId} keys=${lease.originalGlobalValues.keys}")
        val perKey = LinkedHashMap<String, Boolean>()
        for ((path, original) in lease.originalGlobalValues) {
            if (original == LEASE_ABSENT) {
                perKey[path] = true
                continue
            }
            val wrote = try { writeGlobal(path, original) } catch (_: Exception) { false }
            val matched = try { readGlobal(path) == original } catch (_: Exception) { false }
            perKey[path] = wrote && matched
        }
        val allOk = perKey.values.all { it }
        var cleared = false
        if (allOk) {
            clearLease(leaseStore)
            cleared = readLease(leaseStore) == null
        }
        val after = try {
            lease.originalGlobalValues.keys.associateWith { readGlobal(it) }
        } catch (_: Exception) { emptyMap() }
        Log.i(TAG, "S3GAMECFG restore-result reason=$reason title=${lease.titleId} lease=${lease.sessionId} restored=${perKey.count { it.value }}/${perKey.size} globalAfter=$after leaseCleared=$cleared")
        return LeaseEndResult(hadLease = true, allRestored = allOk, leaseCleared = cleared, perKeyOk = perKey)
    }

    /**
     * Restore an unfinished lease left by a crashed session. Call before any
     * title boots so stale title values never leak into the next game.
     * @return true when no lease remains.
     */
    internal fun recoverStaleLease(
        leaseStore: OverrideTierStore,
        readGlobal: (String) -> String?,
        writeGlobal: (String, String) -> Boolean,
    ): Boolean {
        if (readLease(leaseStore) == null) return true
        val result = endScopedLease(leaseStore, readGlobal, writeGlobal, reason = "recover")
        return result.allRestored && result.leaseCleared
    }

    private class CommitTierStore(prefs: SharedPreferences) : OverrideTierStore {
        private val prefsRef = prefs
        override fun getString(key: String): String? = prefsRef.getString(key, null)
        override fun putString(key: String, value: String?) {
            prefsRef.edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.commit()
        }
    }

    private fun leaseStoreOf(context: Context): OverrideTierStore =
        CommitTierStore(context.getSharedPreferences(LEASE_PREFS_FILE, Context.MODE_PRIVATE))

    private fun readGlobalEncoded(path: String): String? = runCatching {
        val node = JSONObject(RPCSX.instance.settingsGetGlobal(path))
        val type = node.optString("type")
        if (type.isBlank()) return@runCatching null
        val display = if (type.equals("bool", ignoreCase = true)) {
            node.optBoolean("value").toString()
        } else {
            if (node.isNull("value")) return@runCatching null
            node.opt("value")?.toString() ?: return@runCatching null
        }
        SettingsValueCodec.encodedFromNode(SettingsValueCodec.SettingNodeSpec(type = type), display)
    }.getOrNull()

    private fun writeGlobalEncoded(path: String, encoded: String): Boolean =
        runCatching { RPCSX.instance.settingsSetGlobal(path, encoded) }.getOrDefault(false)

    /**
     * Device boot entry: recover any stale lease, then snapshot + apply the
     * resolved [titleId] profile. Never mutates global driver preferences.
     */
    fun beginScopedLeaseForBoot(context: Context, titleId: String): ScopedGameSettingsLease? {
        val store = leaseStoreOf(context)
        recoverStaleLease(store, ::readGlobalEncoded, ::writeGlobalEncoded)
        val resolved = resolvedBootOverrides(context, titleId)
        if (resolved.isEmpty()) {
            Log.i(TAG, "S3GAMECFG boot title=$titleId lease=none resolved=empty")
            return null
        }
        val compat = compatibilityDefaultsForTitle(titleId)
        val user = explicitUserOverrides(context, titleId)
        Log.i(TAG, "S3GAMECFG boot title=$titleId compat=$compat user=$user resolved=$resolved")
        return beginScopedLease(store, titleId, resolved, ::readGlobalEncoded, ::writeGlobalEncoded).lease
    }

    /** Device exit entry: restore snapshot globals, then clear the lease. */
    fun endScopedLeaseAfterBoot(context: Context) {
        endScopedLease(leaseStoreOf(context), ::readGlobalEncoded, ::writeGlobalEncoded, reason = "exit")
    }

    /** Device entry: restore a crashed session's lease before another title boots. */
    fun recoverStaleLease(context: Context): Boolean =
        recoverStaleLease(leaseStoreOf(context), ::readGlobalEncoded, ::writeGlobalEncoded)

    // ── Boot replay ──────────────────────────────────────────────────────────

    /**
     * Replay per-title overrides and curated defaults for [titleIdOrNull] before boot.
     */
    fun applyForGame(
        context: Context,
        titleIdOrNull: String?,
        setter: (path: String, value: String) -> Boolean = settingsSetter
    ) {
        val ladder = ladderSequence(storeFactory(context), titleIdOrNull)
        applyLadder(ladder, setter)
    }

    internal fun applyForGame(
        store: OverrideTierStore,
        titleIdOrNull: String?,
        setter: (path: String, value: String) -> Boolean
    ) {
        val ladder = ladderSequence(store, titleIdOrNull)
        applyLadder(ladder, setter)
    }

    /**
     * Replay ONLY the per-title tier.
     */
    fun applyTitleTier(
        context: Context,
        titleId: String?,
        setter: (path: String, value: String) -> Boolean = settingsSetter
    ) {
        applyTitleTier(storeFactory(context), titleId, setter)
    }

    internal fun applyTitleTier(
        store: OverrideTierStore,
        titleId: String?,
        setter: (path: String, value: String) -> Boolean
    ) {
        if (titleId.isNullOrBlank()) return
        val title = SettingsValueCodec.decodeOverrideMap(
            store.getString(KEY_GAME_PREFIX + titleId) ?: "{}"
        )
        val curated = curatedDefaultsForTitle(titleId)
        applyLadder((curated + title).map { it.key to it.value }.asSequence(), setter)
    }

    private fun ladderSequence(store: OverrideTierStore, titleIdOrNull: String?): Sequence<Pair<String, String>> =
        sequence {
            yieldAll(recommendedDefaults.entries.map { it.key to it.value })
            if (!titleIdOrNull.isNullOrBlank()) {
                yieldAll(curatedDefaultsForTitle(titleIdOrNull).entries.map { it.key to it.value })
            }
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
