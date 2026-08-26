package com.zenithblue.sambas3.drivers.download

import android.util.Log
import com.zenithblue.sambas3.drivers.DriverBinaryValidator
import java.io.File
import java.util.zip.ZipFile

object ZipDriverArchiveAdapter {
    private const val TAG = "ZipAdapter"
    private const val MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024
    private const val MAX_ENTRIES = 200

    fun extract(sourceZip: File, destDir: File): Result<Unit> {
        if (!sourceZip.isFile) return Result.failure(IllegalArgumentException("Source not a file"))
        try {
            ZipFile(sourceZip).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > MAX_ENTRIES) return Result.failure(IllegalStateException("Too many entries ${entries.size}"))
                var total = 0L
                for (e in entries) {
                    val name = e.name
                    if (!DriverArchiveInspector.isSafeEntry(name)) return Result.failure(IllegalStateException("Unsafe entry $name"))
                    if (e.name.contains("..") || e.name.startsWith("/") ) return Result.failure(IllegalStateException("Path traversal $name"))
                    total += e.size.coerceAtLeast(0)
                    if (total > MAX_UNCOMPRESSED_BYTES) return Result.failure(IllegalStateException("Uncompressed too large $total"))
                }
                for (e in entries) {
                    if (e.isDirectory) continue
                    val out = File(destDir, e.name)
                    val canonical = out.canonicalFile
                    if (!canonical.toPath().startsWith(destDir.canonicalFile.toPath())) {
                        return Result.failure(IllegalStateException("Entry escapes ${e.name}"))
                    }
                    canonical.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        canonical.outputStream().use { outStream -> input.copyTo(outStream) }
                    }
                }
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Zip extract failed ${e.message}", e)
            return Result.failure(e)
        }
    }

    // Legacy adapt for compatibility: extracts and then uses shared inspector to build output zip
    fun adapt(sourceZip: File, destZip: File): DriverPackageAdapter.Result {
        return DriverPackageAdapter.adapt(sourceZip, destZip)
    }
}
