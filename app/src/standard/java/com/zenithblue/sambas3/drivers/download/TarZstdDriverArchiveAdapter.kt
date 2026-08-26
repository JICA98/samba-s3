package com.zenithblue.sambas3.drivers.download

import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object TarZstdDriverArchiveAdapter {
    private const val TAG = "TzstAdapter"
    private const val MAX_ENTRIES = 200
    private const val MAX_EXPANDED_BYTES = 100L * 1024 * 1024
    private const val MAX_SINGLE_FILE = 50L * 1024 * 1024

    sealed class ExtractResult {
        data class Success(val inspected: DriverArchiveInspector.InspectionResult) : ExtractResult()
        data class Error(val message: String) : ExtractResult()
    }

    fun extract(sourceFile: File, destDir: File, expectedMd5: String? = null): ExtractResult {
        if (!sourceFile.isFile) return ExtractResult.Error("Source not a file")
        if (sourceFile.length() == 0L) return ExtractResult.Error("Source empty")
        // Verify MD5 if provided (transport integrity)
        if (expectedMd5 != null) {
            val actual = md5Hex(sourceFile)
            if (actual == null || !actual.equals(expectedMd5, ignoreCase = true)) {
                return ExtractResult.Error("MD5 mismatch expected=$expectedMd5 actual=$actual")
            }
        }
        try {
            destDir.mkdirs()
            var totalBytes = 0L
            var entryCount = 0
            FileInputStream(sourceFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    ZstdInputStream(bis).use { zstdIn ->
                        TarArchiveInputStream(zstdIn).use { tarIn ->
                            var entry: TarArchiveEntry?
                            while (tarIn.nextTarEntry.also { entry = it } != null) {
                                entryCount++
                                if (entryCount > MAX_ENTRIES) return ExtractResult.Error("Too many entries $entryCount")
                                val e = entry!!
                                val name = e.name
                                if (!DriverArchiveInspector.isSafeEntry(name)) return ExtractResult.Error("Unsafe entry $name")
                                if (e.isSymbolicLink || e.isLink) {
                                    // Reject links that escape destDir
                                    val link = e.linkName
                                    if (link.contains("..") || link.startsWith("/")) return ExtractResult.Error("Unsafe link $name -> $link")
                                    // Skip symlinks to avoid escaping
                                    continue
                                }
                                if (e.isDirectory) {
                                    val dir = File(destDir, name)
                                    val canonical = dir.canonicalFile
                                    if (!canonical.toPath().startsWith(destDir.canonicalFile.toPath())) return ExtractResult.Error("Entry escapes $name")
                                    canonical.mkdirs()
                                    continue
                                }
                                if (e.size > MAX_SINGLE_FILE) return ExtractResult.Error("Single file too large $name ${e.size}")
                                totalBytes += e.size
                                if (totalBytes > MAX_EXPANDED_BYTES) return ExtractResult.Error("Expanded too large $totalBytes")
                                val outFile = File(destDir, name)
                                val canonical = outFile.canonicalFile
                                if (!canonical.toPath().startsWith(destDir.canonicalFile.toPath())) return ExtractResult.Error("Entry escapes $name")
                                canonical.parentFile?.mkdirs()
                                canonical.outputStream().use { out ->
                                    tarIn.copyTo(out)
                                }
                                // Preserve executable? not needed
                            }
                        }
                    }
                }
            }
            // After extraction, flatten one nested dir if needed (common in tzst)
            flattenSingleTopDirIfNeeded(destDir)
            // Inspect for vulkan lib
            val result = DriverArchiveInspector.inspect(destDir)
            return result.fold(
                onSuccess = { ExtractResult.Success(it) },
                onFailure = { ExtractResult.Error(it.message ?: "Inspect failed") }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tzst extract failed ${e.message}", e)
            return ExtractResult.Error(e.message ?: "Unknown tzst error")
        }
    }

    private fun flattenSingleTopDirIfNeeded(dir: File) {
        val topDirs = dir.listFiles()?.filter { it.isDirectory } ?: return
        val topFiles = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (topDirs.size == 1 && topFiles.isEmpty()) {
            val only = topDirs.first()
            // Check if vulkan lib is inside
            val hasVulkanInside = only.walkTopDown().any { it.isFile && it.name.endsWith(".so") }
            val hasVulkanTop = dir.walkTopDown().any { it.isFile && it.name.endsWith(".so") && it.parentFile == dir }
            if (hasVulkanInside && !hasVulkanTop) {
                only.listFiles()?.forEach { child ->
                    val dest = File(dir, child.name)
                    if (!dest.exists()) child.renameTo(dest) else {
                        if (child.isDirectory) {
                            child.copyRecursively(dest, overwrite = true)
                            child.deleteRecursively()
                        } else child.copyTo(dest, overwrite = true)
                    }
                }
                try { only.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun md5Hex(file: File): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(32 * 1024)
                var read: Int
                while (fis.read(buf).also { read = it } != -1) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { null }
    }

    /** Adapt tzst to Samba's expected ZIP format without mutating .so bytes */
    fun adapt(sourceTzst: File, destZip: File, expectedMd5: String? = null): DriverPackageAdapter.Result {
        val tmpDir = createTempDir("tzst_adapt_")
        try {
            val extract = extract(sourceTzst, tmpDir, expectedMd5)
            if (extract is ExtractResult.Error) return DriverPackageAdapter.Result.Error(extract.message)
            val inspected = (extract as ExtractResult.Success).inspected
            // Ensure meta.json exists
            var metaFile = File(tmpDir, "meta.json")
            if (!metaFile.isFile) {
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
                          "libraryName": "${inspected.vulkanLib.name}"
                        }
                        """.trimIndent()
                    )
                }
            } else {
                try {
                    val txt = metaFile.readText()
                    if (!txt.contains(inspected.vulkanLib.name)) {
                        val patched = txt.replace(Regex("\"libraryName\"\\s*:\\s*\"[^\"]+\""), "\"libraryName\": \"${inspected.vulkanLib.name}\"")
                        if (patched != txt) metaFile.writeText(patched)
                    }
                } catch (_: Exception) {}
            }
            // Build dest ZIP preserving .so bytes exactly (flatten)
            destZip.parentFile?.mkdirs()
            if (destZip.exists()) destZip.delete()
            java.util.zip.ZipOutputStream(destZip.outputStream()).use { zos ->
                fun addFile(file: File, entryName: String) {
                    val entry = java.util.zip.ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                addFile(metaFile, "meta.json")
                for (so in inspected.allSoFiles) {
                    val entryName = so.name
                    if (entryName == "meta.json") continue
                    val target = File(tmpDir, entryName)
                    if (so.canonicalFile != target.canonicalFile) {
                        so.copyTo(target, overwrite = true)
                    }
                    addFile(target, entryName)
                }
                val sourceFile = File(tmpDir, "SOURCE.txt")
                if (sourceFile.isFile) addFile(sourceFile, "SOURCE.txt") else {
                    val tmpSource = File(tmpDir, "SOURCE.txt")
                    tmpSource.writeText("Adapted community driver ${sourceTzst.name}\nOriginal lib ${inspected.vulkanLib.name} preserved byte-identical\n")
                    addFile(tmpSource, "SOURCE.txt")
                }
            }
            // Verify destZip
            java.util.zip.ZipFile(destZip).use { checkZip ->
                val checkEntry = checkZip.getEntry(inspected.vulkanLib.name) ?: checkZip.getEntry("libvulkan_freedreno.so")
                if (checkEntry == null) return DriverPackageAdapter.Result.Error("Adapted ZIP missing lib")
            }
            Log.i(TAG, "Adapt success ${sourceTzst.name} -> ${destZip.name} lib=${inspected.vulkanLib.name}")
            return DriverPackageAdapter.Result.Success(destZip)
        } catch (e: Exception) {
            Log.e(TAG, "Adapt failed ${e.message}", e)
            return DriverPackageAdapter.Result.Error(e.message ?: "Unknown adapt error")
        } finally {
            try { tmpDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    private fun createTempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile()
}
