package com.zenithblue.sambas3.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.zenithblue.sambas3.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

private const val GPU_DRIVER_DIRECTORY = "gpu_drivers"
private const val GPU_DRIVER_FILE_REDIRECT_DIR = "gpu/vk_file_redirect"
private const val GPU_DRIVER_INSTALL_TEMP_DIR = "driver_temp"
private const val GPU_DRIVER_STAGING_DIR = "driver_staging"
private const val GPU_DRIVER_META_FILE = "meta.json"
private const val BUNDLED_SYNC_STATE = "bundled_driver_sync_state.json"
private const val TAG = "GPUDriverHelper"

/**
 * Install, enumerate, and (on Play builds) sync bundled Turnip drivers.
 *
 * Loading path (unchanged):
 * UI select → GeneralSettings gpu_driver_path / gpu_driver_name
 * → RPCSX.setCustomDriver(path, libraryName, nativeLibDir)
 * → adrenotools_open_libvulkan(CUSTOM, hookDir, driverDir, libraryName)
 */
object GpuDriverHelper {

    fun getInstalledDrivers(context: Context): Map<File, GpuDriverMetadata> {
        val gpuDriverDir = getDriversDirectory(context)
        val driverMap = mutableMapOf<File, GpuDriverMetadata>()
        driverMap[File("/system/vendor")] = getSystemDriverMetadata()

        gpuDriverDir.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) {
                entry.delete()
                return@forEach
            }
            // Incomplete staging left behind after crash
            if (entry.name.endsWith(".partial") || entry.name.endsWith(".staging")) {
                entry.deleteRecursively()
                return@forEach
            }

            val metadataFile = File(entry.canonicalPath, GPU_DRIVER_META_FILE)
            if (!metadataFile.exists()) {
                entry.deleteRecursively()
                return@forEach
            }

            try {
                val metadata = GpuDriverMetadata.deserialize(metadataFile)
                if (!validateInstalledLibrary(entry, metadata.libraryName)) {
                    Log.w(TAG, "Driver ${entry.name} missing library ${metadata.libraryName}, skipping")
                    return@forEach
                }
                driverMap[entry] = metadata
            } catch (e: SerializationException) {
                Log.w(TAG, "Failed to load gpu driver metadata for ${entry.name}, skipping\n${e.message}")
            }
        }

        return driverMap
    }

    fun getSystemDriverMetadata(): GpuDriverMetadata {
        return GpuDriverMetadata(
            name = "Default",
            author = "",
            packageVersion = "",
            vendor = "",
            driverVersion = "",
            minApi = 0,
            description = "The driver provided by your device system",
            libraryName = "",
        )
    }

    /**
     * Install a driver package from an external stream (user import).
     * Rejected on Play builds where [BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS] is false.
     */
    fun installDriver(context: Context, stream: InputStream): GpuDriverInstallResult {
        if (!BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS) {
            return GpuDriverInstallResult.ExternalInstallDisabled
        }
        return installFromStream(context, stream, installDirName = null, bundledMarker = null)
    }

    fun installDriver(context: Context, file: File): GpuDriverInstallResult {
        if (!BuildConfig.ALLOW_EXTERNAL_GPU_DRIVERS) {
            return GpuDriverInstallResult.ExternalInstallDisabled
        }
        return try {
            file.inputStream().use { installFromStream(context, it, installDirName = null, bundledMarker = null) }
        } catch (e: Exception) {
            Log.e(TAG, "installDriver file failed", e)
            GpuDriverInstallResult.InvalidArchive
        }
    }

    private fun installFromStream(
        context: Context,
        stream: InputStream,
        installDirName: String?,
        bundledMarker: BundledDriverMarker?,
        replaceExisting: Boolean = false,
    ): GpuDriverInstallResult {
        val installTempDir =
            File(context.cacheDir.canonicalPath, GPU_DRIVER_INSTALL_TEMP_DIR).apply {
                deleteRecursively()
                mkdirs()
            }

        try {
            ZipUtil.unzip(stream, installTempDir)
        } catch (e: ZipUtil.ZipSecurityException) {
            Log.e(TAG, "ZIP security rejection: ${e.message}")
            installTempDir.deleteRecursively()
            return GpuDriverInstallResult.SecurityRejected
        } catch (e: Exception) {
            Log.e(TAG, "Invalid archive", e)
            installTempDir.deleteRecursively()
            return GpuDriverInstallResult.InvalidArchive
        }

        return installUnpackedDriver(
            context = context,
            unpackDir = installTempDir,
            installDirName = installDirName,
            bundledMarker = bundledMarker,
            replaceExisting = replaceExisting,
        )
    }

    private fun installUnpackedDriver(
        context: Context,
        unpackDir: File,
        installDirName: String?,
        bundledMarker: BundledDriverMarker?,
        replaceExisting: Boolean,
    ): GpuDriverInstallResult {
        val cleanup = { unpackDir.deleteRecursively() }

        val metadataFile = File(unpackDir, GPU_DRIVER_META_FILE)
        if (!metadataFile.isFile) {
            cleanup()
            return GpuDriverInstallResult.MissingMetadata
        }

        val driverMetadata = try {
            GpuDriverMetadata.deserialize(metadataFile)
        } catch (_: SerializationException) {
            cleanup()
            return GpuDriverInstallResult.InvalidMetadata
        }

        if (driverMetadata.libraryName.isBlank() ||
            driverMetadata.libraryName.contains("..") ||
            driverMetadata.libraryName.contains('/') ||
            driverMetadata.libraryName.contains('\\')
        ) {
            cleanup()
            return GpuDriverInstallResult.InvalidMetadata
        }

        if (!validateInstalledLibrary(unpackDir, driverMetadata.libraryName)) {
            cleanup()
            return GpuDriverInstallResult.MissingLibrary
        }

        if (Build.VERSION.SDK_INT < driverMetadata.minApi) {
            cleanup()
            return GpuDriverInstallResult.UnsupportedAndroidVersion
        }

        val dirName = installDirName?.takeIf { it.isNotBlank() } ?: driverMetadata.label
        if (dirName.contains("..") || dirName.contains('/') || dirName.contains('\\')) {
            cleanup()
            return GpuDriverInstallResult.InvalidMetadata
        }

        val driversRoot = getDriversDirectory(context)
        val finalInstallDir = File(driversRoot, dirName)

        if (finalInstallDir.exists() && !replaceExisting) {
            if (bundledMarker == null) {
                cleanup()
                return GpuDriverInstallResult.AlreadyInstalled
            }
        }

        if (bundledMarker != null) {
            BundledDriverMarker.write(File(unpackDir, BundledDriverMarker.FILE_NAME), bundledMarker)
        }

        // Atomic install via staging rename
        val staging = File(driversRoot, "$dirName.staging")
        staging.deleteRecursively()
        if (!unpackDir.renameTo(staging)) {
            // Cross-filesystem fallback: copy then rename
            try {
                staging.deleteRecursively()
                unpackDir.copyRecursively(staging, overwrite = true)
                unpackDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stage driver", e)
                cleanup()
                staging.deleteRecursively()
                return GpuDriverInstallResult.InvalidArchive
            }
        }

        if (finalInstallDir.exists()) {
            val backup = File(driversRoot, "$dirName.old")
            backup.deleteRecursively()
            if (!finalInstallDir.renameTo(backup)) {
                finalInstallDir.deleteRecursively()
            } else {
                backup.deleteRecursively()
            }
        }

        if (!staging.renameTo(finalInstallDir)) {
            staging.deleteRecursively()
            throw IOException("Failed to finalize driver directory ${finalInstallDir.name}")
        }

        return GpuDriverInstallResult.Success
    }

    fun getLibraryName(context: Context, driverLabel: String): String {
        val driverDir = File(getDriversDirectory(context), driverLabel)
        val metadataFile = File(driverDir, GPU_DRIVER_META_FILE)
        return try {
            GpuDriverMetadata.deserialize(metadataFile).libraryName
        } catch (_: SerializationException) {
            Log.w(
                TAG,
                "Failed to load library name for driver $driverLabel, driver may not exist or have invalid metadata"
            )
            ""
        }
    }

    fun getDriverDirectory(context: Context, driverLabel: String): File =
        File(getDriversDirectory(context), driverLabel)

    fun isBundledDriver(metadata: GpuDriverMetadata): Boolean = metadata.isBundled

    /**
     * Bundled drivers cannot be deleted by the user.
     */
    fun deleteDriver(context: Context, driverDir: File, metadata: GpuDriverMetadata): Boolean {
        if (metadata.isBundled || metadata.name == "Default") {
            Log.w(TAG, "Refusing to delete bundled/system driver ${metadata.label}")
            return false
        }
        if (driverDir.path == File("/system/vendor").path) return false
        return driverDir.deleteRecursively()
    }

    fun ensureFileRedirectDir(context: Context) {
        File(context.getExternalFilesDir(null), GPU_DRIVER_FILE_REDIRECT_DIR).apply {
            if (!isDirectory) {
                delete()
                mkdirs()
            }
        }
    }

    fun getDriversDirectory(context: Context): File =
        File(context.filesDir.canonicalPath, GPU_DRIVER_DIRECTORY).apply {
            if (!isDirectory) {
                delete()
                mkdirs()
            }
        }

    fun resolveInstallResultToString(result: GpuDriverInstallResult) = when (result) {
        GpuDriverInstallResult.Success -> "Successfully installed GPU driver"
        GpuDriverInstallResult.InvalidArchive -> "Invalid GPU driver archive"
        GpuDriverInstallResult.MissingMetadata -> "Selected driver's metadata is missing"
        GpuDriverInstallResult.InvalidMetadata -> "Selected driver's metadata is invalid"
        GpuDriverInstallResult.UnsupportedAndroidVersion -> "Your android version doesn't support selected driver"
        GpuDriverInstallResult.AlreadyInstalled -> "Selected driver is already installed"
        GpuDriverInstallResult.SecurityRejected -> "Driver archive rejected for security reasons"
        GpuDriverInstallResult.MissingLibrary -> "Driver package is missing the Vulkan library"
        GpuDriverInstallResult.ChecksumMismatch -> "Driver package checksum verification failed"
        GpuDriverInstallResult.ExternalInstallDisabled -> "External GPU driver installation is disabled in this build"
    }

    /**
     * Synchronize bundled Turnip packages from Play assets into private storage.
     * Idempotent: skips when installed SHA-256 matches catalog.
     */
    suspend fun syncBundledDrivers(context: Context): BundledDriverSyncResult =
        withContext(Dispatchers.IO) {
            if (!BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) {
                return@withContext BundledDriverSyncResult.Disabled("Bundled Turnip drivers not included in this build")
            }

            val catalog = try {
                context.assets.open(BundledGpuDriverCatalog.ASSET_CATALOG_PATH).use {
                    BundledGpuDriverCatalog.parse(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read bundled driver catalog", e)
                return@withContext BundledDriverSyncResult.Failed(
                    "Bundled driver catalog missing or invalid: ${e.message}"
                )
            }

            if (catalog.drivers.isEmpty()) {
                return@withContext BundledDriverSyncResult.Failed("Bundled driver catalog is empty")
            }

            val installed = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            val replaced = mutableListOf<String>()
            val failures = mutableMapOf<String, String>()

            for (entry in catalog.drivers) {
                try {
                    val result = syncOneBundledDriver(context, entry)
                    when (result) {
                        "installed" -> installed += entry.id
                        "skipped" -> skipped += entry.id
                        "replaced" -> replaced += entry.id
                        else -> failures[entry.id] = result
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bundled sync failed for ${entry.id}", e)
                    failures[entry.id] = e.message ?: "unknown error"
                }
            }

            return@withContext if (failures.isEmpty()) {
                BundledDriverSyncResult.Success(installed, skipped, replaced)
            } else if (installed.isNotEmpty() || skipped.isNotEmpty() || replaced.isNotEmpty()) {
                BundledDriverSyncResult.Partial(installed, skipped, replaced, failures)
            } else {
                BundledDriverSyncResult.Failed("All bundled drivers failed: $failures")
            }
        }

    private fun syncOneBundledDriver(context: Context, entry: BundledGpuDriverEntry): String {
        val assetPath = "${BundledGpuDriverCatalog.ASSET_DIR}/${entry.packageFile}"
        val existingDir = File(getDriversDirectory(context), entry.id)
        val markerFile = File(existingDir, BundledDriverMarker.FILE_NAME)
        if (existingDir.isDirectory && markerFile.isFile) {
            val marker = runCatching { BundledDriverMarker.read(markerFile) }.getOrNull()
            if (marker != null &&
                marker.sha256.equals(entry.sha256, ignoreCase = true) &&
                validateInstalledLibrary(existingDir, entry.libraryName)
            ) {
                return "skipped"
            }
        }

        val hadPrevious = existingDir.isDirectory
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val actualSha = sha256Hex(bytes)
        if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
            Log.e(TAG, "SHA-256 mismatch for ${entry.id}: expected=${entry.sha256} actual=$actualSha")
            return "checksum mismatch"
        }

        val marker = BundledDriverMarker(
            id = entry.id,
            sha256 = entry.sha256,
            displayName = entry.displayName,
            role = entry.role,
            experimental = entry.experimental,
            packageFile = entry.packageFile,
            forceSysmem = false,
        )

        val installResult = bytes.inputStream().use { stream ->
            installFromStream(
                context = context,
                stream = stream,
                installDirName = entry.id,
                bundledMarker = marker,
                replaceExisting = true,
            )
        }

        return when (installResult) {
            GpuDriverInstallResult.Success -> if (hadPrevious) "replaced" else "installed"
            else -> resolveInstallResultToString(installResult)
        }
    }

    fun loadBundledCatalog(context: Context): BundledGpuDriverCatalog? {
        if (!BuildConfig.INCLUDE_BUNDLED_TURNIP_DRIVERS) return null
        return try {
            context.assets.open(BundledGpuDriverCatalog.ASSET_CATALOG_PATH).use {
                BundledGpuDriverCatalog.parse(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled catalog: ${e.message}")
            null
        }
    }

    fun compatibleBundledEntries(context: Context, info: AdrenoGpuInfo = AdrenoGpuDetector.detect()): List<BundledGpuDriverEntry> {
        val catalog = loadBundledCatalog(context) ?: return emptyList()
        return catalog.drivers.filter { AdrenoGpuDetector.isCompatible(it, info) }
    }

    fun resetToSystemDriver(context: Context) {
        GeneralSettings.setValue("selected_gpu_driver", "Default")
        GeneralSettings.setValue("gpu_driver_path", "")
        GeneralSettings.setValue("gpu_driver_name", "")
        GeneralSettings.setValue("gpu_driver_force_sysmem", false)
        GeneralSettings.setValue("gpu_driver_bundled_id", null)
    }

    /**
     * If the saved selection is missing/invalid, fall back to system driver.
     * @return true if selection was reset
     */
    fun ensureValidSelection(context: Context): Boolean {
        val selected = with(GeneralSettings) {
            this["selected_gpu_driver"].string("Default")
        }
        if (selected.isEmpty() || selected == "Default") {
            return false
        }
        val drivers = getInstalledDrivers(context)
        val stillThere = drivers.entries.any { (file, meta) ->
            meta.label == selected || file.name == selected || meta.bundledId == selected
        }
        if (!stillThere) {
            Log.w(TAG, "Selected driver '$selected' missing; resetting to system")
            resetToSystemDriver(context)
            return true
        }
        return false
    }

    fun validateInstalledLibrary(driverDir: File, libraryName: String): Boolean {
        // Delegate to strict validator — ensures ELF64 LE AArch64 + size + stub checks.
        // Keep helper as single integration seam; new logic lives in drivers/DriverBinaryValidator.
        return try {
            com.zenithblue.sambas3.drivers.DriverBinaryValidator.isValid(driverDir, libraryName)
        } catch (_: Exception) {
            false
        }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = sha256Hex(file.readBytes())
}

enum class GpuDriverInstallResult {
    Success,
    InvalidArchive,
    MissingMetadata,
    InvalidMetadata,
    UnsupportedAndroidVersion,
    AlreadyInstalled,
    SecurityRejected,
    MissingLibrary,
    ChecksumMismatch,
    ExternalInstallDisabled,
}
