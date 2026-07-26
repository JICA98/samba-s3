package com.zenithblue.sambas3.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/**
 * Play-only local catalog describing bundled Turnip packages shipped in the APK/AAB.
 */
@Serializable
data class BundledGpuDriverCatalog(
    val schemaVersion: Int = 1,
    val drivers: List<BundledGpuDriverEntry> = emptyList(),
) {
    companion object {
        const val ASSET_DIR = "bundled_gpu_drivers"
        const val CATALOG_FILE = "catalog.json"
        const val ASSET_CATALOG_PATH = "$ASSET_DIR/$CATALOG_FILE"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parse(text: String): BundledGpuDriverCatalog {
            val catalog = json.decodeFromString(serializer(), text)
            require(catalog.schemaVersion == 1) {
                "Unsupported bundled catalog schemaVersion=${catalog.schemaVersion}"
            }
            val ids = catalog.drivers.map { it.id }
            require(ids.size == ids.toSet().size) {
                "Bundled catalog contains duplicate driver ids: $ids"
            }
            return catalog
        }

        fun parse(stream: InputStream): BundledGpuDriverCatalog =
            parse(stream.bufferedReader().use { it.readText() })
    }
}

@Serializable
data class BundledGpuDriverEntry(
    val id: String,
    val displayName: String,
    val role: String,
    val packageFile: String,
    val libraryName: String = "libvulkan_freedreno.so",
    val supportedGpuFamilies: List<String> = emptyList(),
    val supportedGpuIds: List<String> = emptyList(),
    val forceSysmemGpuIds: List<String> = emptyList(),
    val experimental: Boolean = false,
    val sha256: String,
    val sourceVersion: String = "",
    val sourceCommit: String = "",
    val sourceRepo: String = "",
    val notes: String = "",
) {
    val isRecommended: Boolean get() = role.equals("recommended", ignoreCase = true)
    val isCompatibility: Boolean get() = role.equals("compatibility", ignoreCase = true)
    val isExperimental: Boolean get() = experimental || role.equals("experimental", ignoreCase = true)
}

enum class GpuFamily {
    ADRENO_6XX,
    ADRENO_7XX,
    ADRENO_8XX,
    UNKNOWN,
}

data class AdrenoGpuInfo(
    val gpuId: String?,
    val family: GpuFamily,
    val rawModel: String?,
    val isAdreno: Boolean,
    val isArm64: Boolean,
)

sealed class BundledDriverSyncResult {
    data class Success(
        val installed: List<String>,
        val skipped: List<String>,
        val replaced: List<String>,
    ) : BundledDriverSyncResult()

    data class Partial(
        val installed: List<String>,
        val skipped: List<String>,
        val replaced: List<String>,
        val failures: Map<String, String>,
    ) : BundledDriverSyncResult()

    data class Disabled(val reason: String) : BundledDriverSyncResult()
    data class Failed(val message: String) : BundledDriverSyncResult()
}
