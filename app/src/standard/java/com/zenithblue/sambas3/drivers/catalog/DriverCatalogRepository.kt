package com.zenithblue.sambas3.drivers.catalog

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Aggregates all community sources; one source failure does not fail whole list.
 */
object DriverCatalogRepository {

    private val sources: List<DriverSource> = listOf(
        BannerPackJsonSource(), // Arihany (tries multiple URLs, isolated)
        BannerFlatJsonSource(DriverSourceId.KIMCHI, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/kimchi_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.STEVENMXZ, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/stevenmxz_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.MTR, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/mtr_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.WHITE, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/white_drivers.json"),
        BannerFlatJsonSource(DriverSourceId.NIGHTLIES, "https://raw.githubusercontent.com/The412Banner/Nightlies/main/nightlies_components.json", filterGpuOnly = true),
        GitHubReleaseDriverSource()
    )

    suspend fun fetchAll(): List<RemoteDriverPackage> = coroutineScope {
        val deferred = sources.map { src ->
            async {
                try {
                    src.fetch()
                } catch (e: Exception) {
                    Log.w("DriverCatalog", "Source ${src.id} failed: ${e.message}")
                    emptyList<RemoteDriverPackage>()
                }
            }
        }
        val results = deferred.awaitAll()
        val flat = results.flatten()
        Log.i("DriverCatalog", "Fetched ${flat.size} total drivers from ${sources.size} sources (failures isolated)")
        // Sort: recommended/non-experimental first, then alphabetical
        flat.sortedWith(compareBy<RemoteDriverPackage> { it.experimental }.thenBy { it.displayName.lowercase() })
    }

    suspend fun fetchWithIsolatedFailures(): Map<DriverSourceId, List<RemoteDriverPackage>> = coroutineScope {
        val map = mutableMapOf<DriverSourceId, List<RemoteDriverPackage>>()
        val deferred = sources.map { src ->
            async {
                val list = try { src.fetch() } catch (e: Exception) {
                    Log.w("DriverCatalog", "Source ${src.id} exception ${e.message}")
                    emptyList()
                }
                src.id to list
            }
        }
        deferred.awaitAll().forEach { (id, list) -> map[id] = list }
        map
    }
}
