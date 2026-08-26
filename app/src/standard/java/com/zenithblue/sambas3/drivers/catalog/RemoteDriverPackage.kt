package com.zenithblue.sambas3.drivers.catalog

enum class DriverSourceId {
    ARIHANY,
    KIMCHI,
    STEVENMXZ,
    MTR,
    WHITE,
    NIGHTLIES,
    BANNERS_TURNIP,
    BANNERHUB
}

enum class ChecksumAlgorithm { SHA256, MD5 }

data class RemoteChecksum(
    val algorithm: ChecksumAlgorithm,
    val value: String
)

enum class DriverArchiveFormat { ZIP, TZST, UNKNOWN }
enum class DriverGpuFilter { ALL, A6XX, A7XX, A8XX, QUALCOMM, UNKNOWN }
enum class DriverVariantFilter { ALL, TURNIP, QUALCOMM, GMEM, SYSMEM, STANDARD, EXPERIMENTAL }

data class RemoteDriverPackage(
    val id: String,
    val source: DriverSourceId,
    val displayName: String,
    val version: String?,
    val downloadUrl: String,
    val sha256: String? = null,
    val experimental: Boolean = false,
    val gpuHint: String? = null,
    val checksum: RemoteChecksum? = null,
    val archiveFormat: DriverArchiveFormat = inferFormat(downloadUrl),
    val fileSize: Long? = null,
    val variant: String? = null
) {
    companion object {
        fun inferFormat(url: String): DriverArchiveFormat {
            val lower = url.lowercase()
            return when {
                lower.endsWith(".zip") -> DriverArchiveFormat.ZIP
                lower.endsWith(".tzst") || lower.endsWith(".tar.zst") -> DriverArchiveFormat.TZST
                else -> DriverArchiveFormat.UNKNOWN
            }
        }
    }
}

data class DriverSourceSnapshot(
    val source: DriverSourceId,
    val packages: List<RemoteDriverPackage>,
    val error: String? = null,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

data class DriverCatalogSnapshot(
    val sources: List<DriverSourceSnapshot>
) {
    /**
     * Flatten ONCE.
     *
     * Do not make this a custom getter. Browse can recompose many times while
     * typing/searching/downloading and should never repeatedly allocate the
     * entire catalog.
     */
    val packages: List<RemoteDriverPackage> =
        sources.flatMap { it.packages }

    val totalCount: Int = packages.size
}
