package com.zenithblue.sambas3.drivers.catalog

interface DriverSource {
    val id: DriverSourceId
    suspend fun fetch(): List<RemoteDriverPackage>
}
