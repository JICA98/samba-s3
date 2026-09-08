package com.zenithblue.sambas3.utils

import android.content.Context
import android.system.Os
import android.util.Log
import com.zenithblue.sambas3.RPCSX
import java.io.File

private const val TAG = "GpuDriverSelection"

/**
 * Applies the stored GPU driver selection to the native emulator.
 * Handles Adreno 830 SYSMEM hint for the experimental A8XX package when catalog requires it.
 *
 * RDR compatibility (Red Dead Redemption on known-bad Adreno system drivers):
 * [resolveCompatBootDriver] answers whether this boot should use a validated
 * bundled Turnip INSTEAD of the stored selection. It is boot-only: it never
 * writes GeneralSettings. [restoreStoredSelection] re-applies the stored
 * selection after the compat-booted title exits.
 */
object GpuDriverSelection {

    /** Debug-only Turnip diagnostics persisted across the next warm boot. */
    private val allowedTurnipDebugOptions = setOf(
        "nir", "nobin", "sysmem", "gmem", "forcebin", "layout", "nolrz",
        "nolrzfc", "perf", "flushall", "syncdraw", "rast_order",
        "unaligned_store", "log_skip_gmem_ops", "3d_load", "fdm",
        "noconcurrentresolves", "noconcurrentunresolves", "nobinmerging"
    )

    fun setDebugTurnipOptions(raw: String?): Result<String> {
        val value = raw.orEmpty().trim()
        if (value.isEmpty() || value == "-" || value.equals("clear", ignoreCase = true)) {
            GeneralSettings["gpu_driver_tu_debug"] = ""
            return runCatching {
                Os.unsetenv("TU_DEBUG")
                ""
            }
        }
        val options = value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (options.isEmpty() || options.any { it !in allowedTurnipDebugOptions }) {
            val bad = options.firstOrNull { it !in allowedTurnipDebugOptions } ?: value
            return Result.failure(IllegalArgumentException("unsupported TU_DEBUG option: $bad"))
        }
        val normalized = options.distinct().joinToString(",")
        GeneralSettings["gpu_driver_tu_debug"] = normalized
        return runCatching {
            Os.setenv("TU_DEBUG", normalized, true)
            normalized
        }
    }

    private fun applyDebugTurnipOptions() {
        val options = (GeneralSettings["gpu_driver_tu_debug"] as? String).orEmpty().trim()
        if (options.isEmpty()) return
        runCatching { Os.setenv("TU_DEBUG", options, true) }
            .onSuccess { Log.i(TAG, "TU_DEBUG=$options (debug diagnostic)") }
            .onFailure { Log.w(TAG, "Failed to apply debug TU_DEBUG: ${it.message}") }
    }

    fun applyStoredSelection(context: Context, nativeLibraryDir: String): Boolean {
        val path = GeneralSettings["gpu_driver_path"] as? String
        val name = GeneralSettings["gpu_driver_name"] as? String
        if (path.isNullOrBlank() || name.isNullOrBlank()) {
            clearSysmemEnv()
            applyDebugTurnipOptions()
            return RPCSX.instance.setCustomDriver("", "", nativeLibraryDir)
        }

        val driverDir = File(path)
        if (!driverDir.isDirectory || !GpuDriverHelper.validateInstalledLibrary(driverDir, name)) {
            Log.w(TAG, "Stored driver invalid at $path; falling back to system")
            GpuDriverHelper.resetToSystemDriver(context)
            clearSysmemEnv()
            return RPCSX.instance.setCustomDriver("", "", nativeLibraryDir)
        }

        applySysmemIfNeeded(context, driverDir)
        applyDebugTurnipOptions()
        return RPCSX.instance.setCustomDriver(path, name, nativeLibraryDir)
    }

    fun selectDriver(
        context: Context,
        metadata: GpuDriverMetadata,
        driverDir: File?,
        nativeLibraryDir: String,
        forceSysmem: Boolean = false,
    ): Boolean {
        if (metadata.name == "Default" || driverDir == null) {
            clearSysmemEnv()
            val ok = RPCSX.instance.setCustomDriver("", "", nativeLibraryDir)
            if (ok) {
                GeneralSettings.setValue("selected_gpu_driver", "Default")
                GeneralSettings["gpu_driver_path"] = ""
                GeneralSettings["gpu_driver_name"] = ""
                GeneralSettings["gpu_driver_force_sysmem"] = false
                GeneralSettings["gpu_driver_bundled_id"] = null
            }
            return ok
        }

        if (!GpuDriverHelper.validateInstalledLibrary(driverDir, metadata.libraryName)) {
            return false
        }

        if (forceSysmem) {
            setSysmemEnv()
        } else {
            clearSysmemEnv()
        }

        val ok = RPCSX.instance.setCustomDriver(
            driverDir.path,
            metadata.libraryName,
            nativeLibraryDir,
        )
        if (ok) {
            GeneralSettings.setValue("selected_gpu_driver", metadata.label)
            GeneralSettings["gpu_driver_path"] = driverDir.path
            GeneralSettings["gpu_driver_name"] = metadata.libraryName
            GeneralSettings["gpu_driver_force_sysmem"] = forceSysmem
            GeneralSettings["gpu_driver_bundled_id"] = metadata.bundledId
        }
        return ok
    }

    fun shouldForceSysmemForSelection(context: Context, metadata: GpuDriverMetadata): Boolean {
        if (!metadata.isBundled || metadata.bundledId == null) return false
        val catalog = GpuDriverHelper.loadBundledCatalog(context) ?: return false
        val entry = catalog.drivers.find { it.id == metadata.bundledId } ?: return false
        val info = AdrenoGpuDetector.detect()
        return AdrenoGpuDetector.shouldForceSysmem(entry, info)
    }

    // ── RDR compatibility driver (boot-only, never persisted) ──────────────

    /** RDR family; only BLUS30758 has on-device Turnip validation, the rest are family mapping. */
    val RDR_COMPAT_FAMILY = setOf(
        "BLUS30758", "BLES01294", "BLUS30418", "BLES00680", "BLJM60233", "BLJM60395", "BLAS50404"
    )

    /** Device-validated member of the RDR family. */
    const val RDR_VALIDATED_TITLE = "BLUS30758"

    data class CurrentDriverSelection(val selectedLabel: String, val driverPath: String) {
        companion object {
            fun read(): CurrentDriverSelection = CurrentDriverSelection(
                selectedLabel = (GeneralSettings["selected_gpu_driver"] as? String)?.ifBlank { "Default" } ?: "Default",
                driverPath = (GeneralSettings["gpu_driver_path"] as? String).orEmpty()
            )
        }
    }

    data class CompatBootDriverSpec(
        val entryId: String,
        val libraryName: String,
        val label: String,
        val reason: String,
    )

    data class CompatBootDriver(
        val driverDir: File,
        val libraryName: String,
        val label: String,
        val bundledId: String,
        val reason: String,
    )

    fun isRdrCompatTitle(titleId: String?): Boolean =
        !titleId.isNullOrBlank() && RDR_COMPAT_FAMILY.contains(titleId.uppercase())

    /**
     * Pure compatibility decision. Non-null only when ALL hold:
     * RDR-family title + Adreno GPU + user still on system/default + a
     * compatible installed bundled Turnip validates. Explicit user driver
     * choice always wins (null). Mali/non-Adreno always null.
     */
    fun resolveCompatBootDriver(
        titleId: String?,
        gpuInfo: AdrenoGpuInfo,
        current: CurrentDriverSelection,
        compatibleEntries: List<BundledGpuDriverEntry>,
        installedBundledIds: Set<String>,
    ): CompatBootDriverSpec? {
        if (!isRdrCompatTitle(titleId)) return null
        if (!gpuInfo.isAdreno) {
            Log.i(TAG, "S3GPU compat title=$titleId verdict=system reason=non-adreno")
            return null
        }
        if (current.selectedLabel != "Default" || current.driverPath.isNotBlank()) {
            Log.i(TAG, "S3GPU compat title=$titleId verdict=stored reason=explicit-user-driver")
            return null
        }
        val entry = chooseCompatEntry(compatibleEntries, installedBundledIds)
        if (entry == null) {
            Log.w(TAG, "S3GPU compat title=$titleId verdict=system reason=turnip-missing")
            return null
        }
        val reason = "rdr-adreno-system+turnip:${entry.id}"
        Log.i(TAG, "S3GPU compat title=$titleId verdict=turnip entry=${entry.id} reason=$reason")
        return CompatBootDriverSpec(entry.id, entry.libraryName, entry.displayName, reason)
    }

    /**
     * Rank compatible entries: recommended role first, then compatibility
     * role; experimental entries are never auto-selected. First entry whose
     * bundled id is installed wins.
     */
    internal fun chooseCompatEntry(
        compatibleEntries: List<BundledGpuDriverEntry>,
        installedBundledIds: Set<String>,
    ): BundledGpuDriverEntry? {
        val installed = compatibleEntries.filter { it.id in installedBundledIds && !it.isExperimental }
        return installed.minByOrNull { entry ->
            when {
                entry.isRecommended -> 0
                entry.isCompatibility -> 1
                else -> 2
            }
        }
    }

    /**
     * Device wrapper: loads catalog + installed set, validates the library,
     * and returns the boot-only driver. Never writes GeneralSettings.
     */
    fun resolveCompatBootDriverForBoot(context: Context, titleId: String?): CompatBootDriver? {
        val info = AdrenoGpuDetector.detect()
        val current = CurrentDriverSelection.read()
        val entries = GpuDriverHelper.compatibleBundledEntries(context, info)
        val installed = GpuDriverHelper.getInstalledDrivers(context)
        val installedIds = installed.values.mapNotNull { it.bundledId }.toSet()
        val spec = resolveCompatBootDriver(titleId, info, current, entries, installedIds) ?: return null
        val dir = installed.entries.firstOrNull { it.value.bundledId == spec.entryId }?.key ?: return null
        if (!GpuDriverHelper.validateInstalledLibrary(dir, spec.libraryName)) {
            Log.w(TAG, "S3GPU compat title=$titleId entry=${spec.entryId} verdict=system reason=library-invalid")
            return null
        }
        return CompatBootDriver(dir, spec.libraryName, spec.label, spec.entryId, spec.reason)
    }

    /**
     * Apply a compat driver for this boot only (no preference mutation), or
     * re-apply the stored selection after a compat-booted title exits.
     */
    fun applyCompatBootDriver(override: CompatBootDriver, nativeLibraryDir: String): Boolean {
        val ok = RPCSX.instance.setCustomDriver(override.driverDir.path, override.libraryName, nativeLibraryDir)
        Log.i(TAG, "S3GPU compat-apply label=${override.label} ok=$ok")
        return ok
    }

    fun restoreStoredSelection(context: Context, nativeLibraryDir: String): Boolean {
        val beforeLabel = (GeneralSettings["selected_gpu_driver"] as? String)?.ifBlank { "Default" } ?: "Default"
        val ok = applyStoredSelection(context, nativeLibraryDir)
        val afterLabel = (GeneralSettings["selected_gpu_driver"] as? String)?.ifBlank { "Default" } ?: "Default"
        Log.i(TAG, "S3GPU restore-stored before=$beforeLabel after=$afterLabel ok=$ok")
        return ok
    }

    private fun applySysmemIfNeeded(context: Context, driverDir: File) {
        val force = with(GeneralSettings) {
            this["gpu_driver_force_sysmem"].boolean(false)
        }
        val bundledId = GeneralSettings["gpu_driver_bundled_id"] as? String
        if (force) {
            setSysmemEnv()
            return
        }
        if (bundledId != null) {
            val catalog = GpuDriverHelper.loadBundledCatalog(context)
            val entry = catalog?.drivers?.find { it.id == bundledId }
            if (entry != null && AdrenoGpuDetector.shouldForceSysmem(entry, AdrenoGpuDetector.detect())) {
                setSysmemEnv()
                return
            }
        }
        // Also honour marker written at install time
        val markerFile = File(driverDir, BundledDriverMarker.FILE_NAME)
        if (markerFile.isFile) {
            val marker = runCatching { BundledDriverMarker.read(markerFile) }.getOrNull()
            if (marker?.forceSysmem == true) {
                setSysmemEnv()
                return
            }
        }
        clearSysmemEnv()
    }

    /**
     * Mesa Turnip reads TU_DEBUG. Setting sysmem is the upstream recommendation for Adreno 830
     * with experimental A8XX packages. Applied only for that selection path — not global policy.
     */
    private fun setSysmemEnv() {
        try {
            val existing = runCatching { Os.getenv("TU_DEBUG") }.getOrNull()
            val next = if (existing.isNullOrBlank()) {
                "sysmem"
            } else if (existing.split(',').any { it.trim().equals("sysmem", ignoreCase = true) }) {
                existing
            } else {
                "$existing,sysmem"
            }
            Os.setenv("TU_DEBUG", next, true)
            Log.i(TAG, "TU_DEBUG=$next (Adreno SYSMEM hint)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set TU_DEBUG sysmem: ${e.message}")
        }
    }

    private fun clearSysmemEnv() {
        try {
            val existing = runCatching { Os.getenv("TU_DEBUG") }.getOrNull() ?: return
            val parts = existing.split(',').map { it.trim() }.filter {
                it.isNotEmpty() && !it.equals("sysmem", ignoreCase = true)
            }
            if (parts.isEmpty()) {
                Os.unsetenv("TU_DEBUG")
            } else {
                Os.setenv("TU_DEBUG", parts.joinToString(","), true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear TU_DEBUG sysmem: ${e.message}")
        }
    }
}
