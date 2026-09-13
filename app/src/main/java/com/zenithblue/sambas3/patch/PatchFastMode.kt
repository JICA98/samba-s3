package com.zenithblue.sambas3.patch

import android.content.Context
import android.util.Log
import com.zenithblue.sambas3.PatchRepository
import com.zenithblue.sambas3.PpuReadinessStore
import com.zenithblue.sambas3.RPCSX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-game Patch Fast Mode orchestrator.
 * Combines curated, glitch-free high-performance patches to maximize FPS.
 */
object PatchFastMode {
    private const val TAG = "PatchFastMode"

    // Curated high-performance, glitch-free patch presets per game family
    // Specifically curated to deliver maximum FPS boost with ZERO graphical glitches
    private val CURATED_FAST_PATCHES: Map<String, Set<String>> = mapOf(
        // The Last of Us (BCUS98174 / NPUA80960 / BCES01584 / BCES01585):
        // "Disable in-built MLAA" eliminates heavy SPU post-processing.
        // "Disable Motion Blur" saves GPU RSX & SPU compute.
        // "Skip Intro" bypasses intros straight to menu.
        // NOTE: "Disable Depth of Field", "Disable Bloom", and "Enable GPU Lighting"
        // are excluded because on v01.00 they introduce severe checkered/quad visual glitches.
        "BCUS98174" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),
        "NPUA80960" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),
        "BCES01584" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),
        "BCES01585" to setOf("Disable in-built MLAA", "Disable Motion Blur", "Skip Intro"),

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

    // Fallback keyword matchers for unlisted games
    private val FAST_KEYWORDS = listOf(
        "disable in-built mlaa",
        "disable mlaa",
        "disable motion blur",
        "skip intro",
        "disable fxaa",
        "disable blur",
    )

    fun fastPatchNamesForTitle(titleId: String?): Set<String> {
        if (titleId.isNullOrBlank()) return emptySet()
        val upper = titleId.uppercase()
        CURATED_FAST_PATCHES[upper]?.let { return it }
        val titlePatches = PatchRepository.forTitle(PatchRepository.list(), upper)
        return titlePatches.filter { patch ->
            val lower = patch.name.lowercase()
            FAST_KEYWORDS.any { lower.contains(it) }
        }.map { it.name }.toSet()
    }

    suspend fun isFastModeEnabled(titleId: String?): Boolean = withContext(Dispatchers.IO) {
        if (titleId.isNullOrBlank()) return@withContext false
        val upper = titleId.uppercase()
        val targets = fastPatchNamesForTitle(upper)
        if (targets.isEmpty()) return@withContext false
        val titlePatches = PatchRepository.forTitle(PatchRepository.list(), upper)
        val enabledNames = titlePatches.filter { it.enabled }.map { it.name }.toSet()
        targets.all { it in enabledNames }
    }

    suspend fun setFastModeEnabled(
        titleId: String?,
        enabled: Boolean,
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (titleId.isNullOrBlank()) return@withContext false
        val upper = titleId.uppercase()
        val targets = fastPatchNamesForTitle(upper)
        if (targets.isEmpty()) return@withContext false

        val patches = PatchRepository.list()
        val forGame = PatchRepository.forTitle(patches, upper)
        val grouped = PatchRepository.group(forGame)
        var anyChanged = false

        for (group in grouped) {
            if (group.name in targets) {
                val ok = PatchRepository.setEnabled(group, enabled, upper)
                if (ok) anyChanged = true
            }
        }

        PatchRepository.invalidate()

        if (context != null) {
            runCatching {
                val manifest = RPCSX.instance.getPpuManifestKey(upper)
                if (!manifest.isNullOrBlank()) {
                    PpuReadinessStore.syncFingerprint(context, upper, manifest)
                }
            }.onFailure { Log.w(TAG, "failed syncing fingerprint: ${it.message}") }
        }

        anyChanged
    }
}
