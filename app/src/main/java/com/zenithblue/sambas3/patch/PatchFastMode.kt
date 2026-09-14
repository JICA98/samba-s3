package com.zenithblue.sambas3.patch

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.PatchRepository
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-game Fast Mode orchestrator.
 *
 * Capability is strictly per-title: a game is Fast Mode capable only when it has
 * an explicit curated profile. There is no generic keyword fallback.
 *
 * Enabled state is persisted per title ID. Curated patches are applied when
 * present; missing patches must not prevent Fast Mode settings from applying
 * at the next boot.
 */
object PatchFastMode {
    private const val TAG = "PatchFastMode"
    const val PREFS_FILE = "sambas3_fast_mode"
    private const val KEY_PREFIX = "enabled."

    /** JVM-test / in-process mirror of the per-title enabled flag. */
    internal val enabledByTitle = ConcurrentHashMap<String, Boolean>()

    // Curated high-performance, glitch-free patch presets per game family
    // Specifically curated to deliver maximum FPS boost with ZERO graphical glitches
    private val CURATED_FAST_PATCHES: Map<String, Set<String>> = mapOf(
        // Grand Theft Auto V (BLJM61019, BLUS31156, BLES01807, NPUB31156, NPEB01807, NPJB00517, NPUB31154, NPJB00516, NPEB01283)
        "BLJM61019" to setOf("Skip Rockstar Boot Logo"),
        "BLUS31156" to setOf("Skip Rockstar Boot Logo"),
        "BLES01807" to setOf("Skip Rockstar Boot Logo"),
        "NPUB31156" to setOf("Skip Rockstar Boot Logo"),
        "NPEB01807" to setOf("Skip Rockstar Boot Logo"),
        "NPJB00517" to setOf("Skip Rockstar Boot Logo"),
        "NPUB31154" to setOf("Skip Rockstar Boot Logo"),
        "NPJB00516" to setOf("Skip Rockstar Boot Logo"),
        "NPEB01283" to setOf("Skip Rockstar Boot Logo"),

        // The Last of Us (BCUS98174 / NPUA80960 / BCES01584 / BCES01585):
        // "Disable in-built MLAA" eliminates heavy SPU post-processing.
        // "Disable Motion Blur" saves GPU RSX & SPU compute.
        // "Disable SSAO" disables heavy SPU ambient occlusion passes cleanly.
        // "Skip Intro" bypasses intros straight to menu.
        // NOTE: "Disable Depth of Field", "Disable Bloom", and "Enable GPU Lighting"
        // are excluded because on v01.00 they introduce severe checkered/quad visual glitches.
        "BCUS98174" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Disable SSAO", "Skip Intro"),
        "NPUA80960" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Disable SSAO", "Skip Intro"),
        "BCES01584" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Disable SSAO", "Skip Intro"),
        "BCES01585" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Disable SSAO", "Skip Intro"),

        // Uncharted 2: Among Thieves
        "BCUS98123" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),
        "BCES00509" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),

        // inFAMOUS 2
        "BCUS98125" to setOf("Disable in-built MLAA", "Disable Motion Blur"),

        // Red Dead Redemption
        "BLUS30758" to setOf("Disable in-built MLAA", "Disable Motion Blur"),
        "BLES00680" to setOf("Disable in-built MLAA", "Disable Motion Blur"),
        "BLES01294" to setOf("Disable in-built MLAA", "Disable Motion Blur"),
    )

    /**
     * Extra engine settings applied at boot when Fast Mode is ON for [titleId].
     * These overlay compatibility defaults and explicit user overrides so the
     * Fast Mode toggle is the source of truth for its own knobs.
     * Empty for titles whose Fast Mode is patch-only.
     */
    private val FAST_MODE_SETTINGS: Map<String, Map<String, String>> = mapOf(
        // Pass 2: Driver Wake-Up Delay 1 removes the 200µs RSX submit stall copied
        // from RDR. SPU Block Size Mega is deferred — TLOU rejected Mega, and GTA V
        // still SIGSEGVs in Normal before a Mega cache rebuild can be justified.
        "BLJM61019" to gtaVFastModeSettings(),
        "BLUS31156" to gtaVFastModeSettings(),
        "BLES01807" to gtaVFastModeSettings(),
        "NPUB31156" to gtaVFastModeSettings(),
        "NPEB01807" to gtaVFastModeSettings(),
        "NPJB00517" to gtaVFastModeSettings(),
        "NPUB31154" to gtaVFastModeSettings(),
        "NPJB00516" to gtaVFastModeSettings(),
        "NPEB01283" to gtaVFastModeSettings(),
    )

    private fun gtaVFastModeSettings(): Map<String, String> = mapOf(
        "Video@@Driver Wake-Up Delay" to "1",
    )

    /**
     * Fast Mode capability is strictly per-game.
     * Returns true ONLY if the game has an explicit curated Fast Mode profile.
     * Generic fallback for unlisted games is explicitly rejected.
     */
    fun isFastModeSupported(titleId: String?): Boolean {
        if (titleId.isNullOrBlank()) return false
        return CURATED_FAST_PATCHES.containsKey(titleId.uppercase())
    }

    fun fastPatchNamesForTitle(titleId: String?): Set<String> {
        if (titleId.isNullOrBlank()) return emptySet()
        val upper = titleId.uppercase()
        return CURATED_FAST_PATCHES[upper] ?: emptySet()
    }

    fun fastModeSettingsForTitle(titleId: String?): Map<String, String> {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) return emptyMap()
        return FAST_MODE_SETTINGS[titleId.uppercase()].orEmpty()
    }

    private fun prefsKey(titleId: String) = KEY_PREFIX + titleId.uppercase()

    internal fun persistEnabled(context: Context?, titleId: String, enabled: Boolean) {
        val upper = titleId.uppercase()
        enabledByTitle[upper] = enabled
        if (context != null) {
            runCatching {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(prefsKey(upper), enabled)
                    .apply()
            }.onFailure { Log.w(TAG, "failed persisting Fast Mode for $upper: ${it.message}") }
        }
    }

    /**
     * Synchronous enabled check used at boot and in the launcher.
     * Prefs / in-memory flag win. Missing patches do not imply Fast Mode is off.
     * Unsupported titles always return false (stale flags cannot leak).
     */
    fun isFastModeEnabledSync(context: Context?, titleId: String?): Boolean {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) return false
        val upper = titleId.uppercase()
        enabledByTitle[upper]?.let { return it }
        if (context != null) {
            val prefs = runCatching {
                context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            }.getOrNull()
            if (prefs != null && prefs.contains(prefsKey(upper))) {
                return prefs.getBoolean(prefsKey(upper), false)
            }
        }
        val targets = fastPatchNamesForTitle(upper)
        if (targets.isEmpty()) return false
        val titlePatches = runCatching {
            PatchRepository.forTitle(PatchRepository.list(), upper)
        }.getOrDefault(emptyList())
        if (titlePatches.isEmpty()) return false
        val enabledNames = titlePatches.filter { it.enabled }.map { it.name }.toSet()
        return targets.all { it in enabledNames }
    }

    suspend fun isFastModeEnabled(titleId: String?, context: Context? = null): Boolean =
        withContext(Dispatchers.IO) { isFastModeEnabledSync(context, titleId) }

    suspend fun setFastModeEnabled(
        titleId: String?,
        enabled: Boolean,
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) return@withContext false
        val upper = titleId.uppercase()
        persistEnabled(context, upper, enabled)

        val targets = fastPatchNamesForTitle(upper)
        if (targets.isNotEmpty()) {
            runCatching {
                val patches = PatchRepository.list()
                val forGame = PatchRepository.forTitle(patches, upper)
                val grouped = PatchRepository.group(forGame)
                for (group in grouped) {
                    if (group.name in targets) {
                        PatchRepository.setEnabled(group, enabled, upper)
                    }
                }
                PatchRepository.invalidate()
            }.onFailure { Log.w(TAG, "Fast Mode patch apply failed for $upper: ${it.message}") }
        }

        if (context != null) {
            runCatching {
                val manifest = RPCSX.instance.getPpuManifestKey(upper)
                if (!manifest.isNullOrBlank()) {
                    PpuReadinessStore.syncFingerprint(context, upper, manifest)
                }
            }.onFailure { Log.w(TAG, "failed syncing fingerprint: ${it.message}") }
        }

        true
    }
}
