package com.zenithblue.sambas3.drivers.download

import android.util.Log
import com.zenithblue.sambas3.drivers.DriverBinaryValidator
import java.io.File
import java.util.zip.ZipFile

/**
 * Adapts community ZIP layouts to Samba's expected format without mutating Vulkan .so bytes.
 * - Locates libvulkan_freedreno.so or compatible libvulkan*.so
 * - Flattens one safe nested directory
 * - Preserves sibling .so dependencies
 * - Creates Samba metadata when missing
 * - Rejects path traversal, missing lib, invalid ELF, wrong arch, absurd expansion
 */
object DriverPackageAdapter {

    private const val TAG = "DriverPackageAdapter"
    private const val MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024 // 100 MiB cap
    private const val MAX_ENTRIES = 100

    sealed class Result {
        data class Success(val adaptedFile: File) : Result()
        data class Error(val message: String) : Result()
    }

    fun adapt(sourceFile: File, destZip: File, expectedChecksum: com.zenithblue.sambas3.drivers.catalog.RemoteChecksum? = null): Result {
        val format = DriverArchiveFormat.detect(sourceFile)
        return when (format) {
            ArchiveFormat.TZST -> {
                val md5 = if (expectedChecksum?.algorithm == com.zenithblue.sambas3.drivers.catalog.ChecksumAlgorithm.MD5) expectedChecksum.value else null
                TarZstdDriverArchiveAdapter.adapt(sourceFile, destZip, md5)
            }
            ArchiveFormat.ZIP -> adaptZip(sourceFile, destZip)
            else -> {
                // Try zip as fallback, then tzst
                val zipTry = adaptZip(sourceFile, destZip)
                if (zipTry is Result.Success) zipTry else {
                    val md5 = if (expectedChecksum?.algorithm == com.zenithblue.sambas3.drivers.catalog.ChecksumAlgorithm.MD5) expectedChecksum.value else null
                    TarZstdDriverArchiveAdapter.adapt(sourceFile, destZip, md5)
                }
            }
        }
    }

    private fun adaptZip(sourceZip: File, destZip: File): Result {
        return try {
            // Basic checks
            if (!sourceZip.isFile) return Result.Error("Source not a file")
            if (sourceZip.length() == 0L) return Result.Error("Source empty")

            ZipFile(sourceZip).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) return Result.Error("Too many entries ${entries.size}")
                var totalUncompressed = 0L
                for (e in entries) {
                    // Path traversal check
                    if (e.name.contains("..") || e.name.startsWith("/") || e.name.contains("\\")) {
                        return Result.Error("Path traversal in ${e.name}")
                    }
                    totalUncompressed += e.size.coerceAtLeast(0)
                    if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                        return Result.Error("Uncompressed too large $totalUncompressed")
                    }
                }

                // Find Vulkan lib
                var libEntry = entries.find { it.name.endsWith("libvulkan_freedreno.so") && !it.isDirectory }
                if (libEntry == null) {
                    // Fallback to any libvulkan*.so
                    libEntry = entries.find { it.name.lowercase().endsWith(".so") && it.name.lowercase().contains("vulkan") && !it.isDirectory }
                }
                if (libEntry == null) return Result.Error("No Vulkan library found")

                // Extract to temp dir
                val tmpDir = createTempDir("adapt_", null)
                try {
                    // Extract all preserving structure but flatten one nested dir if needed
                    for (e in entries) {
                        if (e.isDirectory) continue
                        val out = File(tmpDir, e.name)
                        // Ensure parent inside tmpDir
                        val canonical = out.canonicalFile
                        if (!canonical.toPath().startsWith(tmpDir.canonicalFile.toPath())) {
                            return Result.Error("Entry escapes temp dir ${e.name}")
                        }
                        canonical.parentFile?.mkdirs()
                        zip.getInputStream(e).use { input ->
                            canonical.outputStream().use { outStream -> input.copyTo(outStream) }
                        }
                    }

                    // Flatten one nested dir if lib is nested
                    var libFile = File(tmpDir, libEntry.name)
                    if (!libFile.exists()) {
                        // Search
                        libFile = tmpDir.walkTopDown().firstOrNull { it.name == "libvulkan_freedreno.so" || it.name.lowercase().contains("vulkan") && it.name.endsWith(".so") } ?: libFile
                    }
                    // If lib is in subdirectory and tmpDir has single top-level dir, flatten
                    val topLevelDirs = tmpDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
                    val topLevelFiles = tmpDir.listFiles()?.filter { it.isFile } ?: emptyList()
                    if (libFile.parentFile != tmpDir && topLevelDirs.size == 1 && topLevelFiles.isEmpty()) {
                        val onlyDir = topLevelDirs.first()
                        // Move contents up one level if safe
                        onlyDir.listFiles()?.forEach { child ->
                            val dest = File(tmpDir, child.name)
                            if (dest.exists()) return@forEach
                            child.renameTo(dest)
                        }
                        try { onlyDir.delete() } catch (_: Exception) {}
                        // Re-find libFile
                        libFile = tmpDir.walkTopDown().firstOrNull { it.name == libFile.name } ?: libFile
                    }

                    // Ensure libFile exists after flatten
                    if (!libFile.isFile) {
                        libFile = tmpDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".so") && it.name.contains("vulkan") } ?: return Result.Error("Vulkan lib not found after flatten")
                    }

                    // Validate ELF via shared validator (use tmpDir as driverDir, libFile name)
                    val driverDir = libFile.parentFile ?: tmpDir
                    val libName = libFile.name
                    val validation = DriverBinaryValidator.validate(driverDir, libName)
                    if (!validation.ok) {
                        return Result.Error("Invalid Vulkan ELF: ${validation.reason}")
                    }

                    // Check for additional .so dependencies in tmpDir (preserve)
                    val soFiles = tmpDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()

                    // Ensure meta.json exists, create if missing
                    var metaFile = File(tmpDir, "meta.json")
                    if (!metaFile.isFile) {
                        // Search nested
                        metaFile = tmpDir.walkTopDown().firstOrNull { it.name == "meta.json" } ?: File(tmpDir, "meta.json")
                        if (!metaFile.isFile) {
                            metaFile.writeText(
                                """
                                {
                                  "schemaVersion": 1,
                                  "name": "Community Turnip",
                                  "author": "Community",
                                  "packageVersion": "1",
                                  "vendor": "Mesa",
                                  "driverVersion": "unknown",
                                  "minApi": 28,
                                  "description": "Adapted community driver",
                                  "libraryName": "${libFile.name}"
                                }
                                """.trimIndent()
                            )
                            Log.i(TAG, "Created missing meta.json for $libName")
                        }
                    } else {
                        // Ensure libraryName matches actual lib
                        try {
                            val txt = metaFile.readText()
                            if (!txt.contains(libFile.name)) {
                                // Patch libraryName field if mismatched
                                val patched = txt.replace(Regex("\"libraryName\"\\s*:\\s*\"[^\"]+\""), "\"libraryName\": \"${libFile.name}\"")
                                if (patched != txt) metaFile.writeText(patched)
                            }
                        } catch (_: Exception) {}
                    }

                    // Build dest ZIP preserving .so bytes exactly
                    destZip.parentFile?.mkdirs()
                    if (destZip.exists()) destZip.delete()
                    java.util.zip.ZipOutputStream(destZip.outputStream()).use { zos ->
                        // Add meta.json
                        fun addFile(file: File, entryName: String) {
                            val entry = java.util.zip.ZipEntry(entryName)
                            zos.putNextEntry(entry)
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                        addFile(metaFile, "meta.json")
                        // Add all .so files at top level (flatten)
                        for (so in soFiles) {
                            // Ensure we don't add duplicate entry name
                            val entryName = so.name
                            // Already added if so == meta? No
                            // Use so file directly to preserve bytes
                            val target = File(tmpDir, entryName) // top-level
                            if (so.canonicalFile != target.canonicalFile) {
                                // Copy to top-level for zipping if needed
                                so.copyTo(target, overwrite = true)
                            }
                            // Avoid adding meta.json again
                            if (entryName == "meta.json") continue
                            addFile(target, entryName)
                        }
                        // Add SOURCE.txt if exists, else create
                        val sourceFile = File(tmpDir, "SOURCE.txt")
                        if (sourceFile.isFile) addFile(sourceFile, "SOURCE.txt") else {
                            val tmpSource = File(tmpDir, "SOURCE.txt")
                            tmpSource.writeText("Adapted community driver ${sourceZip.name}\nOriginal lib ${libFile.name} preserved byte-identical\n")
                            addFile(tmpSource, "SOURCE.txt")
                        }
                    }

                    // Verify destZip can be opened and lib still valid
                    ZipFile(destZip).use { checkZip ->
                        val checkEntry = checkZip.getEntry(libFile.name) ?: checkZip.getEntry("libvulkan_freedreno.so")
                        if (checkEntry == null) return Result.Error("Adapted ZIP missing lib")
                    }

                    Log.i(TAG, "Adapt success ${sourceZip.name} -> ${destZip.name} lib=${libFile.name} bytes=${libFile.length()}")
                    Result.Success(destZip)
                } finally {
                    try { tmpDir.deleteRecursively() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Adapt failed for ${sourceZip.name}: ${e.message}", e)
            Result.Error(e.message ?: "Unknown adapt error")
        }
    }

    private fun createTempDir(prefix: String, suffix: String?): File {
        return java.nio.file.Files.createTempDirectory(prefix).toFile()
    }
}
