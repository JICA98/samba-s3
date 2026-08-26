package com.zenithblue.sambas3.drivers.catalog

enum class DriverSourceId {
    ARIHANY,
    KIMCHI,
    STEVENMXZ,
    MTR,
    WHITE,
    NIGHTLIES,
    BANNERS_TURNIP
}

data class RemoteDriverPackage(
    val id: String,
    val source: DriverSourceId,
    val displayName: String,
    val version: String?,
    val downloadUrl: String,
    val sha256: String? = null,
    val experimental: Boolean = false,
    val gpuHint: String? = null
)
