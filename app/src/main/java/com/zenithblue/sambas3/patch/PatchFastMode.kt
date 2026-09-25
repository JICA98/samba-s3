package com.zenithblue.sambas3.patch

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.PatchRepository
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import com.zenithblue.sambas3.gameconfig.SettingsValueCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class FastModeState {
    EFFECTIVE,             // All curated patches applied & settings active
    PARTIAL,               // Some patches applied or settings active
    REQUESTED_NOT_APPLIED, // User requested Fast Mode ON, but 0 matching patches found
    UNAVAILABLE,           // Game has no curated Fast Mode profile
    DISABLED,              // User requested Fast Mode OFF
    FAILED                 // Exception during application
}

data class FastModeReceipt(
    val titleId: String,
    val state: FastModeState,
    val requested: Boolean,
    val targetPatchCount: Int,
    val appliedPatches: List<String>,
    val missingPatches: List<String>,
    val effectiveSettings: Map<String, String>,
    val message: String
)

enum class FastPatchExperiment {
    ALL,                // All curated patches enabled (production default)
    MLAA_ONLY,          // E05: MLAA removal alone
    MOTION_BLUR_ONLY,   // E06 single: Motion Blur removal alone
    COMBINED_NO_INTRO,  // E06 combined: MLAA + Motion Blur removal (gameplay only, no intro skip)
    SKIP_INTRO_ONLY     // Control: Skip intro / boot logo only
}

/**
 * Per-game Fast Mode orchestrator.
 *
 * Capability is strictly per-title: a game is Fast Mode capable only when it has
 * an explicit curated profile. There is no generic keyword fallback.
 *
 * Enabled state is persisted per title ID. Curated patches and engine settings
 * are returned via a structured [FastModeReceipt]. If user requested Fast Mode ON
 * but zero matching patches are found, Fast Mode honestly reports [FastModeState.REQUESTED_NOT_APPLIED].
 */
object PatchFastMode {
    private const val TAG = "PatchFastMode"
    const val PREFS_FILE = "sambas3_fast_mode"
    private const val KEY_PREFIX = "enabled."

    /** JVM-test / in-process mirror of the per-title requested flag. */
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

        // God of War III
        "BCUS98111" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
        "BCES00510" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
        "BCES00799" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
        "BCJS37001" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
        "BCAS25003" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
        "BCKS15003" to setOf("Disable MLAA", "Disable Motion Blur", "Skip intro"),
    )

    /**
     * Extra engine settings applied at boot when Fast Mode is active for [titleId].
     * These overlay compatibility defaults and explicit user overrides so the
     * Fast Mode toggle is the source of truth for its own knobs.
     * Empty for titles whose Fast Mode is patch-only.
     */
    private val FAST_MODE_SETTINGS: Map<String, Map<String, String>> = mapOf(
        // GTA V: Driver Wake-Up Delay 1 removes the 200µs RSX submit stall.
        "BLJM61019" to gtaVFastModeSettings(),
        "BLUS31156" to gtaVFastModeSettings(),
        "BLES01807" to gtaVFastModeSettings(),
        "NPUB31156" to gtaVFastModeSettings(),
        "NPEB01807" to gtaVFastModeSettings(),
        "NPJB00517" to gtaVFastModeSettings(),
        "NPUB31154" to gtaVFastModeSettings(),
        "NPJB00516" to gtaVFastModeSettings(),
        "NPEB01283" to gtaVFastModeSettings(),

        // God of War III: Measured engine profile (RPCS3 scheduler affinity, bounded compiler concurrency, optimal SPU block size)
        "BCUS98111" to gowFastModeSettings(),
        "BCES00510" to gowFastModeSettings(),
        "BCES00799" to gowFastModeSettings(),
        "BCJS37001" to gowFastModeSettings(),
        "BCAS25003" to gowFastModeSettings(),
        "BCKS15003" to gowFastModeSettings(),
    )

    private fun gtaVFastModeSettings(): Map<String, String> = mapOf(
        "Video@@Driver Wake-Up Delay" to "1",
    )

    private fun gowFastModeSettings(): Map<String, String> = mapOf(
        "Core@@Thread Scheduler Mode" to SettingsValueCodec.quoteCfgString("RPCS3 Scheduler"),
        "Core@@Max LLVM Compile Threads" to "2",
        "Core@@SPU Block Size" to SettingsValueCodec.quoteCfgString("Mega"),
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

    fun fastPatchNamesForTitle(
        titleId: String?,
        experiment: FastPatchExperiment = FastPatchExperiment.ALL
    ): Set<String> {
        if (titleId.isNullOrBlank()) return emptySet()
        val allTargets = CURATED_FAST_PATCHES[titleId.uppercase()] ?: return emptySet()
        return when (experiment) {
            FastPatchExperiment.ALL -> allTargets
            FastPatchExperiment.MLAA_ONLY -> allTargets.filter {
                it.contains("mlaa", ignoreCase = true)
            }.toSet()
            FastPatchExperiment.MOTION_BLUR_ONLY -> allTargets.filter {
                it.contains("motion blur", ignoreCase = true)
            }.toSet()
            FastPatchExperiment.COMBINED_NO_INTRO -> allTargets.filter {
                it.contains("mlaa", ignoreCase = true) || it.contains("motion blur", ignoreCase = true)
            }.toSet()
            FastPatchExperiment.SKIP_INTRO_ONLY -> allTargets.filter {
                it.contains("intro", ignoreCase = true) || it.contains("boot logo", ignoreCase = true)
            }.toSet()
        }
    }

    fun fastPatchNamesForTitle(
        titleId: String?,
        enableMlaa: Boolean,
        enableMotionBlur: Boolean,
        enableSkipIntro: Boolean = true,
        enableOther: Boolean = true
    ): Set<String> {
        if (titleId.isNullOrBlank()) return emptySet()
        val allTargets = CURATED_FAST_PATCHES[titleId.uppercase()] ?: return emptySet()
        return allTargets.filter { name ->
            when {
                name.contains("mlaa", ignoreCase = true) -> enableMlaa
                name.contains("motion blur", ignoreCase = true) -> enableMotionBlur
                name.contains("intro", ignoreCase = true) || name.contains("boot logo", ignoreCase = true) -> enableSkipIntro
                else -> enableOther
            }
        }.toSet()
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

    fun isFastModeRequestedSync(context: Context?, titleId: String?): Boolean {
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
        return false
    }

    suspend fun isFastModeRequested(titleId: String?, context: Context? = null): Boolean =
        withContext(Dispatchers.IO) { isFastModeRequestedSync(context, titleId) }

    fun getFastModeReceiptSync(context: Context?, titleId: String?): FastModeReceipt {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) {
            return FastModeReceipt(
                titleId = titleId.orEmpty(),
                state = FastModeState.UNAVAILABLE,
                requested = false,
                targetPatchCount = 0,
                appliedPatches = emptyList(),
                missingPatches = emptyList(),
                effectiveSettings = emptyMap(),
                message = "Fast Mode is unavailable for ${titleId ?: "unknown"}"
            )
        }

        val upper = titleId.uppercase()
        val isRequested = isFastModeRequestedSync(context, upper)
        val targets = fastPatchNamesForTitle(upper, FastPatchExperiment.ALL)
        val settings = fastModeSettingsForTitle(upper)

        if (!isRequested) {
            return FastModeReceipt(
                titleId = upper,
                state = FastModeState.DISABLED,
                requested = false,
                targetPatchCount = targets.size,
                appliedPatches = emptyList(),
                missingPatches = emptyList(),
                effectiveSettings = emptyMap(),
                message = "Fast Mode is disabled"
            )
        }

        val titlePatches = runCatching {
            PatchRepository.forTitle(PatchRepository.list(context), upper)
        }.getOrDefault(emptyList())

        val enabledNames = titlePatches.filter { it.enabled }.map { it.name }.toSet()
        val appliedPatches = targets.filter { target ->
            enabledNames.any { it.equals(target, ignoreCase = true) }
        }
        val missingPatches = targets.filter { target ->
            !enabledNames.any { it.equals(target, ignoreCase = true) }
        }

        val state = when {
            targets.isNotEmpty() && appliedPatches.size == targets.size -> FastModeState.EFFECTIVE
            targets.isNotEmpty() && appliedPatches.isNotEmpty() -> FastModeState.PARTIAL
            targets.isNotEmpty() && appliedPatches.isEmpty() -> FastModeState.REQUESTED_NOT_APPLIED
            targets.isEmpty() && settings.isNotEmpty() -> FastModeState.EFFECTIVE
            else -> FastModeState.REQUESTED_NOT_APPLIED
        }

        val effectiveSettings = if (state == FastModeState.EFFECTIVE || state == FastModeState.PARTIAL) {
            settings
        } else {
            emptyMap()
        }

        val message = when (state) {
            FastModeState.EFFECTIVE -> "Fast Mode effective: ${appliedPatches.size}/${targets.size} patches applied"
            FastModeState.PARTIAL -> "Fast Mode partial: ${appliedPatches.size}/${targets.size} patches applied"
            FastModeState.REQUESTED_NOT_APPLIED -> "Fast Mode requested, but 0/${targets.size} matching patches found"
            else -> "Fast Mode disabled"
        }

        return FastModeReceipt(
            titleId = upper,
            state = state,
            requested = true,
            targetPatchCount = targets.size,
            appliedPatches = appliedPatches,
            missingPatches = missingPatches,
            effectiveSettings = effectiveSettings,
            message = message
        )
    }

    suspend fun getFastModeReceipt(titleId: String?, context: Context? = null): FastModeReceipt =
        withContext(Dispatchers.IO) { getFastModeReceiptSync(context, titleId) }

    /**
     * Synchronous enabled check used at boot and in the launcher.
     * Returns true ONLY if Fast Mode optimizations are actually applied (EFFECTIVE or PARTIAL).
     * If requested but 0 matching patches are found, returns false (honest optimization state).
     */
    fun isFastModeEnabledSync(context: Context?, titleId: String?): Boolean {
        val receipt = getFastModeReceiptSync(context, titleId)
        return receipt.state == FastModeState.EFFECTIVE || receipt.state == FastModeState.PARTIAL
    }

    suspend fun isFastModeEnabled(titleId: String?, context: Context? = null): Boolean =
        withContext(Dispatchers.IO) { isFastModeEnabledSync(context, titleId) }

    suspend fun setFastModeEnabled(
        titleId: String?,
        enabled: Boolean,
        context: Context? = null,
        experiment: FastPatchExperiment = FastPatchExperiment.ALL
    ): FastModeReceipt = withContext(Dispatchers.IO) {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) {
            return@withContext FastModeReceipt(
                titleId = titleId.orEmpty(),
                state = FastModeState.UNAVAILABLE,
                requested = false,
                targetPatchCount = 0,
                appliedPatches = emptyList(),
                missingPatches = emptyList(),
                effectiveSettings = emptyMap(),
                message = "Fast Mode is unavailable for title $titleId"
            )
        }

        val upper = titleId.uppercase()
        persistEnabled(context, upper, enabled)

        val allCurated = fastPatchNamesForTitle(upper, FastPatchExperiment.ALL)

        if (!enabled) {
            runCatching {
                if (context != null) PatchRepository.ensureBundledPatches(context)
                val patches = PatchRepository.list(context)
                val forGame = PatchRepository.forTitle(patches, upper)
                val grouped = PatchRepository.group(forGame)
                for (group in grouped) {
                    if (allCurated.any { it.equals(group.name, ignoreCase = true) }) {
                        PatchRepository.setEnabled(group, false, upper)
                    }
                }
                PatchRepository.invalidate()
            }.onFailure { Log.w(TAG, "Fast Mode patch disable failed for $upper: ${it.message}") }

            if (context != null) {
                syncPpuFingerprint(context, upper)
            }

            return@withContext FastModeReceipt(
                titleId = upper,
                state = FastModeState.DISABLED,
                requested = false,
                targetPatchCount = allCurated.size,
                appliedPatches = emptyList(),
                missingPatches = emptyList(),
                effectiveSettings = emptyMap(),
                message = "Fast Mode disabled for $upper"
            )
        }

        val targetPatches = fastPatchNamesForTitle(upper, experiment)
        val toDisable = allCurated - targetPatches

        val appliedPatches = mutableListOf<String>()
        val missingPatches = mutableListOf<String>()

        try {
            if (context != null) {
                PatchRepository.ensureBundledPatches(context)
            }
            val patches = PatchRepository.list(context)
            val forGame = PatchRepository.forTitle(patches, upper)
            val grouped = PatchRepository.group(forGame)

            for (target in targetPatches) {
                val group = grouped.find { it.name.equals(target, ignoreCase = true) }
                if (group != null) {
                    val ok = PatchRepository.setEnabled(group, true, upper)
                    if (ok) {
                        appliedPatches.add(group.name)
                    } else {
                        missingPatches.add(target)
                    }
                } else {
                    missingPatches.add(target)
                }
            }

            for (disabledName in toDisable) {
                val group = grouped.find { it.name.equals(disabledName, ignoreCase = true) }
                if (group != null) {
                    PatchRepository.setEnabled(group, false, upper)
                }
            }

            PatchRepository.invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "Fast Mode patch apply failed for $upper: ${t.message}", t)
            return@withContext FastModeReceipt(
                titleId = upper,
                state = FastModeState.FAILED,
                requested = true,
                targetPatchCount = targetPatches.size,
                appliedPatches = appliedPatches,
                missingPatches = missingPatches + (targetPatches - appliedPatches.toSet() - missingPatches.toSet()),
                effectiveSettings = emptyMap(),
                message = "Failed applying Fast Mode: ${t.message}"
            )
        }

        if (context != null) {
            syncPpuFingerprint(context, upper)
        }

        val settings = fastModeSettingsForTitle(upper)
        val state = when {
            targetPatches.isNotEmpty() && appliedPatches.size == targetPatches.size -> FastModeState.EFFECTIVE
            targetPatches.isNotEmpty() && appliedPatches.isNotEmpty() -> FastModeState.PARTIAL
            targetPatches.isNotEmpty() && appliedPatches.isEmpty() -> FastModeState.REQUESTED_NOT_APPLIED
            targetPatches.isEmpty() && settings.isNotEmpty() -> FastModeState.EFFECTIVE
            else -> FastModeState.REQUESTED_NOT_APPLIED
        }

        val effectiveSettings = if (state == FastModeState.EFFECTIVE || state == FastModeState.PARTIAL) {
            settings
        } else {
            emptyMap()
        }

        val message = when (state) {
            FastModeState.EFFECTIVE -> "Fast Mode effective: ${appliedPatches.size}/${targetPatches.size} curated patches applied"
            FastModeState.PARTIAL -> "Fast Mode partial: ${appliedPatches.size}/${targetPatches.size} curated patches applied"
            FastModeState.REQUESTED_NOT_APPLIED -> "Fast Mode requested, but 0/${targetPatches.size} matching patches found"
            else -> "Fast Mode inactive"
        }

        FastModeReceipt(
            titleId = upper,
            state = state,
            requested = true,
            targetPatchCount = targetPatches.size,
            appliedPatches = appliedPatches,
            missingPatches = missingPatches,
            effectiveSettings = effectiveSettings,
            message = message
        )
    }

    suspend fun applyFastModeExperiment(
        titleId: String?,
        experiment: FastPatchExperiment,
        context: Context? = null
    ): FastModeReceipt = setFastModeEnabled(titleId, true, context, experiment)

    suspend fun setFastModeGranular(
        titleId: String?,
        enableMlaa: Boolean,
        enableMotionBlur: Boolean,
        enableSkipIntro: Boolean = true,
        enableOther: Boolean = true,
        context: Context? = null
    ): FastModeReceipt = withContext(Dispatchers.IO) {
        if (titleId.isNullOrBlank() || !isFastModeSupported(titleId)) {
            return@withContext FastModeReceipt(
                titleId = titleId.orEmpty(),
                state = FastModeState.UNAVAILABLE,
                requested = false,
                targetPatchCount = 0,
                appliedPatches = emptyList(),
                missingPatches = emptyList(),
                effectiveSettings = emptyMap(),
                message = "Fast Mode is unavailable for $titleId"
            )
        }
        val upper = titleId.uppercase()
        val targets = fastPatchNamesForTitle(
            upper,
            enableMlaa = enableMlaa,
            enableMotionBlur = enableMotionBlur,
            enableSkipIntro = enableSkipIntro,
            enableOther = enableOther
        )
        val allCurated = fastPatchNamesForTitle(upper, FastPatchExperiment.ALL)
        val toDisable = allCurated - targets

        val requested = targets.isNotEmpty()
        persistEnabled(context, upper, requested)

        val appliedPatches = mutableListOf<String>()
        val missingPatches = mutableListOf<String>()

        try {
            if (context != null) {
                PatchRepository.ensureBundledPatches(context)
            }
            val patches = PatchRepository.list(context)
            val forGame = PatchRepository.forTitle(patches, upper)
            val grouped = PatchRepository.group(forGame)

            for (target in targets) {
                val group = grouped.find { it.name.equals(target, ignoreCase = true) }
                if (group != null) {
                    val ok = PatchRepository.setEnabled(group, true, upper)
                    if (ok) {
                        appliedPatches.add(group.name)
                    } else {
                        missingPatches.add(target)
                    }
                } else {
                    missingPatches.add(target)
                }
            }

            for (disabledName in toDisable) {
                val group = grouped.find { it.name.equals(disabledName, ignoreCase = true) }
                if (group != null) {
                    PatchRepository.setEnabled(group, false, upper)
                }
            }

            PatchRepository.invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "Fast Mode granular patch apply failed for $upper: ${t.message}", t)
            return@withContext FastModeReceipt(
                titleId = upper,
                state = FastModeState.FAILED,
                requested = requested,
                targetPatchCount = targets.size,
                appliedPatches = appliedPatches,
                missingPatches = missingPatches + (targets - appliedPatches.toSet() - missingPatches.toSet()),
                effectiveSettings = emptyMap(),
                message = "Failed applying granular Fast Mode: ${t.message}"
            )
        }

        if (context != null) {
            syncPpuFingerprint(context, upper)
        }

        val settings = fastModeSettingsForTitle(upper)
        val state = when {
            !requested -> FastModeState.DISABLED
            targets.isNotEmpty() && appliedPatches.size == targets.size -> FastModeState.EFFECTIVE
            targets.isNotEmpty() && appliedPatches.isNotEmpty() -> FastModeState.PARTIAL
            targets.isNotEmpty() && appliedPatches.isEmpty() -> FastModeState.REQUESTED_NOT_APPLIED
            targets.isEmpty() && settings.isNotEmpty() -> FastModeState.EFFECTIVE
            else -> FastModeState.REQUESTED_NOT_APPLIED
        }

        val effectiveSettings = if (state == FastModeState.EFFECTIVE || state == FastModeState.PARTIAL) {
            settings
        } else {
            emptyMap()
        }

        val message = when (state) {
            FastModeState.EFFECTIVE -> "Fast Mode effective: ${appliedPatches.size}/${targets.size} curated patches applied"
            FastModeState.PARTIAL -> "Fast Mode partial: ${appliedPatches.size}/${targets.size} curated patches applied"
            FastModeState.REQUESTED_NOT_APPLIED -> "Fast Mode requested, but 0/${targets.size} matching patches found"
            FastModeState.DISABLED -> "Fast Mode disabled for $upper"
            else -> "Fast Mode inactive"
        }

        FastModeReceipt(
            titleId = upper,
            state = state,
            requested = requested,
            targetPatchCount = targets.size,
            appliedPatches = appliedPatches,
            missingPatches = missingPatches,
            effectiveSettings = effectiveSettings,
            message = message
        )
    }

    private fun syncPpuFingerprint(context: Context, upper: String) {
        runCatching {
            val manifest = RPCSX.instance.getPpuManifestKey(upper)
            if (!manifest.isNullOrBlank()) {
                PpuReadinessStore.syncFingerprint(context, upper, manifest)
            }
        }.onFailure { Log.w(TAG, "failed syncing fingerprint: ${it.message}") }
    }
}
