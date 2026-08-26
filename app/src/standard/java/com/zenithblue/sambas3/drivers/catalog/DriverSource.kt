package com.zenithblue.sambas3.drivers.catalog

interface DriverSource {
    val id: DriverSourceId
    suspend fun fetch(): List<RemoteDriverPackage>
    suspend fun fetchResult(): DriverSourceSnapshot {
        val start = System.currentTimeMillis()
        return try {
            val pkgs = fetch()
            DriverSourceSnapshot(id, pkgs, null, start)
        } catch (e: Exception) {
            DriverSourceSnapshot(id, emptyList(), e.message ?: "fetch failed", start)
        }
    }
}
