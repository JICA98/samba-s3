package com.zenithblue.sambas3.utils

import java.io.File

/**
 * Pure filtering for which installed drivers the Play UI should offer.
 */
object BundledDriverVisibility {

    fun filterForDevice(
        installed: Map<File, GpuDriverMetadata>,
        info: AdrenoGpuInfo,
        catalogEntries: List<BundledGpuDriverEntry>,
        supportsCustomDriverLoading: Boolean,
    ): Map<File, GpuDriverMetadata> {
        if (!info.isArm64 || !info.isAdreno || !supportsCustomDriverLoading) {
            return installed.filter { it.value.name == "Default" }
        }

        val catalogById = catalogEntries.associateBy { it.id }
        return installed.filter { (_, meta) ->
            when {
                meta.name == "Default" -> true
                !meta.isBundled -> false
                else -> {
                    val entry = catalogById[meta.bundledId]
                    if (entry != null) {
                        AdrenoGpuDetector.isCompatible(entry, info)
                    } else {
                        when {
                            meta.bundledId?.contains("a8xx") == true ->
                                info.family == GpuFamily.ADRENO_8XX
                            else ->
                                info.family == GpuFamily.ADRENO_6XX ||
                                    info.family == GpuFamily.ADRENO_7XX
                        }
                    }
                }
            }
        }
    }
}
