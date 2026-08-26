package com.zenithblue.sambas3.drivers.catalog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Aggregates all community sources; one source failure does not fail whole list.
 * Network work explicitly on Dispatchers.IO under supervisorScope.
 */
object DriverCatalogRepository {

    private val sources: List<DriverSource> = listOf(
        BannerHubManifestDriverSource(),
        BannerPackJsonSource(), // Arihany (tries multiple URLs, isolated)
        BannerFlatJsonSource(DriverSourceId.KIMCHI, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/kimchi_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.STEVENMXZ, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/stevenmxz_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.MTR, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/mtr_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.WHITE, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/white_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.NIGHTLIES, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/nightlies_components.json", filterGpuOnly = true),
        GitHubReleaseDriverSource()
    )

    suspend fun fetchAll(): List<RemoteDriverPackage> {
        val snap = refresh()
        val flat = snap.packages
        Log.i("DriverCatalog", "Fetched ${flat.size} total drivers from ${sources.size} sources (failures isolated)")
        return flat.sortedWith(compareBy<RemoteDriverPackage> { it.experimental }.thenBy { it.displayName.lowercase() })
    }

    suspend fun refresh(): DriverCatalogSnapshot = supervisorScope {
        val deferred = sources.map { src ->
            async(Dispatchers.IO) {
                val snap = src.fetchResult()
                if (snap.error != null) Log.w("DriverCatalog", "Source ${snap.source} failed: ${snap.error} count=${snap.packages.size}")
                else Log.i("DriverCatalog", "Source ${snap.source} ok count=${snap.packages.size}")
                snap
            }
        }
        val snaps = deferred.awaitAll()
        // Dedupe exact duplicates by canonical download URL
        val seen = mutableSetOf<String>()
        val dedupedSnaps = snaps.map { snap ->
            val deduped = snap.packages.filter { pkg ->
                val key = pkg.downloadUrl.trim()
                if (key in seen) false else { seen.add(key); true }
            }
            snap.copy(packages = deduped)
        }
        DriverCatalogSnapshot(dedupedSnaps)
    }

    suspend fun fetchWithIsolatedFailures(): Map<DriverSourceId, List<RemoteDriverPackage>> {
        val snap = refresh()
        return snap.sources.associate { it.source to it.packages }
    }

    fun filterDrivers(
        packages: List<RemoteDriverPackage>,
        query: String,
        source: DriverSourceId?,
        gpu: DriverGpuFilter?,
        variant: DriverVariantFilter?,
        hideExperimental: Boolean,
        latestOnly: Boolean = false
    ): List<RemoteDriverPackage> {
        var result = packages
        if (!query.isBlank()) {
            val q = query.lowercase()
            result = result.filter {
                it.displayName.lowercase().contains(q) ||
                it.source.name.lowercase().contains(q) ||
                (it.version?.lowercase()?.contains(q) == true) ||
                (it.gpuHint?.lowercase()?.contains(q) == true) ||
                (it.variant?.lowercase()?.contains(q) == true)
            }
        }
        if (source != null) result = result.filter { it.source == source }
        if (gpu != null && gpu != DriverGpuFilter.ALL) {
            result = result.filter { pkg ->
                val hint = (pkg.gpuHint ?: pkg.displayName).lowercase()
                when (gpu) {
                    DriverGpuFilter.A6XX -> hint.contains("a6xx") || hint.contains("6xx")
                    DriverGpuFilter.A7XX -> hint.contains("a7xx") || hint.contains("7xx")
                    DriverGpuFilter.A8XX -> hint.contains("a8xx") || hint.contains("8xx") || hint.contains("840") || hint.contains("830")
                    DriverGpuFilter.QUALCOMM -> hint.contains("qualcomm") || hint.contains("adpkg") || hint.contains("qcom")
                    DriverGpuFilter.UNKNOWN -> pkg.gpuHint == null
                    else -> true
                }
            }
        }
        if (variant != null && variant != DriverVariantFilter.ALL) {
            result = result.filter { pkg ->
                val n = (pkg.displayName + " " + (pkg.variant ?: "")).lowercase()
                when (variant) {
                    DriverVariantFilter.TURNIP -> n.contains("turnip")
                    DriverVariantFilter.QUALCOMM -> n.contains("qualcomm") || n.contains("adpkg")
                    DriverVariantFilter.GMEM -> n.contains("gmem")
                    DriverVariantFilter.SYSMEM -> n.contains("sysmem")
                    DriverVariantFilter.STANDARD -> n.contains("standard") || (!n.contains("gmem") && !n.contains("sysmem") && n.contains("turnip"))
                    DriverVariantFilter.EXPERIMENTAL -> pkg.experimental
                    else -> true
                }
            }
        }
        if (hideExperimental) result = result.filter { !it.experimental }
        if (latestOnly) {
            // Keep only latest per source+base name heuristic: group by displayName prefix before version
            result = result.groupBy { it.source to it.displayName.substringBefore(" —") }.values.mapNotNull { group -> group.maxByOrNull { it.version ?: "" } }
        }
        return result
    }
}
