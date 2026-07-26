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
 */
object GpuDriverSelection {

    fun applyStoredSelection(context: Context, nativeLibraryDir: String): Boolean {
        val path = GeneralSettings["gpu_driver_path"] as? String
        val name = GeneralSettings["gpu_driver_name"] as? String
        if (path.isNullOrBlank() || name.isNullOrBlank()) {
            clearSysmemEnv()
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
