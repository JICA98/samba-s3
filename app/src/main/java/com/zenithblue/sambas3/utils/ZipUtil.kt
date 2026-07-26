package com.zenithblue.sambas3.utils

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Secure ZIP extraction for GPU driver packages.
 *
 * Rejects ZIP-slip, absolute paths, oversized archives, and excessive entry counts.
 */
object ZipUtil {

    const val DEFAULT_MAX_ENTRIES = 64
    const val DEFAULT_MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    const val DEFAULT_MAX_TOTAL_BYTES = 128L * 1024L * 1024L

    data class Limits(
        val maxEntries: Int = DEFAULT_MAX_ENTRIES,
        val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    )

    class ZipSecurityException(message: String) : IOException(message)

    @Throws(IOException::class)
    fun unzip(file: File, targetDirectory: File, limits: Limits = Limits()) {
        ZipFile(file).use { zipFile ->
            var entryCount = 0
            var totalBytes = 0L
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val zipEntry = entries.nextElement()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw ZipSecurityException("Archive exceeds max entry count (${limits.maxEntries})")
                }
                validateEntryName(zipEntry.name)
                if (zipEntry.isDirectory) {
                    val destDir = createNewFile(targetDirectory, zipEntry)
                    if (!destDir.isDirectory && !destDir.mkdirs()) {
                        throw FileNotFoundException("Failed to create destination directory: $destDir")
                    }
                    continue
                }
                val size = zipEntry.size
                if (size >= 0) {
                    if (size > limits.maxEntryBytes) {
                        throw ZipSecurityException("Entry exceeds max size: ${zipEntry.name}")
                    }
                    totalBytes += size
                    if (totalBytes > limits.maxTotalBytes) {
                        throw ZipSecurityException("Archive exceeds max total uncompressed size")
                    }
                }
                val destFile = createNewFile(targetDirectory, zipEntry)
                val destDirectory = destFile.parentFile
                if (destDirectory == null || (!destDirectory.isDirectory && !destDirectory.mkdirs())) {
                    throw FileNotFoundException("Failed to create destination directory: $destDirectory")
                }
                try {
                    var written = 0L
                    zipFile.getInputStream(zipEntry).use { inputStream ->
                        destFile.outputStream().use { outputStream ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = inputStream.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > limits.maxEntryBytes) {
                                    destFile.delete()
                                    throw ZipSecurityException("Entry exceeds max size while extracting: ${zipEntry.name}")
                                }
                                outputStream.write(buffer, 0, read)
                            }
                        }
                    }
                    if (size < 0) {
                        totalBytes += written
                        if (totalBytes > limits.maxTotalBytes) {
                            destFile.delete()
                            throw ZipSecurityException("Archive exceeds max total uncompressed size")
                        }
                    }
                } catch (e: IOException) {
                    if (destFile.exists()) destFile.delete()
                    throw e
                }
            }
        }
    }

    @Throws(IOException::class)
    fun unzip(stream: InputStream, targetDirectory: File, limits: Limits = Limits()) {
        ZipInputStream(BufferedInputStream(stream)).use { zis ->
            var entryCount = 0
            var totalBytes = 0L
            while (true) {
                val zipEntry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw ZipSecurityException("Archive exceeds max entry count (${limits.maxEntries})")
                }
                validateEntryName(zipEntry.name)
                if (zipEntry.isDirectory) {
                    val destDir = createNewFile(targetDirectory, zipEntry)
                    if (!destDir.isDirectory && !destDir.mkdirs()) {
                        throw FileNotFoundException("Failed to create destination directory: $destDir")
                    }
                    continue
                }
                val declared = zipEntry.size
                if (declared >= 0 && declared > limits.maxEntryBytes) {
                    throw ZipSecurityException("Entry exceeds max size: ${zipEntry.name}")
                }
                val destFile = createNewFile(targetDirectory, zipEntry)
                val destDirectory = destFile.parentFile
                if (destDirectory == null || (!destDirectory.isDirectory && !destDirectory.mkdirs())) {
                    throw FileNotFoundException("Failed to create destination directory: $destDirectory")
                }
                try {
                    var written = 0L
                    BufferedOutputStream(destFile.outputStream()).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zis.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > limits.maxEntryBytes) {
                                destFile.delete()
                                throw ZipSecurityException("Entry exceeds max size while extracting: ${zipEntry.name}")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    totalBytes += written
                    if (totalBytes > limits.maxTotalBytes) {
                        destFile.delete()
                        throw ZipSecurityException("Archive exceeds max total uncompressed size")
                    }
                } catch (e: IOException) {
                    if (destFile.exists()) destFile.delete()
                    throw e
                }
            }
        }
    }

    @Throws(IOException::class)
    internal fun validateEntryName(name: String) {
        if (name.isEmpty()) {
            throw ZipSecurityException("Empty ZIP entry name")
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            throw ZipSecurityException("Absolute path in ZIP entry: $name")
        }
        // Windows-style drive letter
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) {
            throw ZipSecurityException("Absolute path in ZIP entry: $name")
        }
        val normalized = name.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) {
            throw ZipSecurityException("Path traversal in ZIP entry: $name")
        }
    }

    @Throws(IOException::class)
    private fun createNewFile(destinationDir: File, zipEntry: ZipEntry): File {
        val destFile = File(destinationDir, zipEntry.name.replace('\\', '/'))
        val destDirPath = destinationDir.canonicalPath
        val destFilePath = destFile.canonicalPath

        if (destFilePath != destDirPath && !destFilePath.startsWith(destDirPath + File.separator)) {
            throw ZipSecurityException("Entry is outside of the target dir: ${zipEntry.name}")
        }

        return destFile
    }
}
