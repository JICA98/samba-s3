package com.zenithblue.sambas3.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies a stream into a same-directory temporary file and publishes it only
 * after the complete byte count has been verified and synced to storage.
 */
internal object AtomicFileCopier {
    private const val BUFFER_SIZE = 64 * 1024

    @Throws(IOException::class)
    fun copy(input: InputStream, target: File, expectedSize: Long?): Long {
        require(expectedSize == null || expectedSize >= 0L) {
            "expectedSize must be null or non-negative"
        }

        val absoluteTarget = target.absoluteFile
        val parent = absoluteTarget.parentFile
            ?: throw IOException("Import target has no parent: ${absoluteTarget.path}")

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create import directory: ${parent.path}")
        }
        if (!parent.isDirectory) {
            throw IOException("Import parent is not a directory: ${parent.path}")
        }

        val temporary = File.createTempFile(
            ".sambas3-${absoluteTarget.name.take(48)}-",
            ".importing",
            parent,
        )
        var published = false

        try {
            var copied = 0L
            FileOutputStream(temporary, false).use { rawOutput ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        rawOutput.write(singleByte)
                        copied++
                    } else {
                        rawOutput.write(buffer, 0, count)
                        copied += count
                    }
                }
                rawOutput.flush()
                rawOutput.fd.sync()
            }

            if (expectedSize != null && copied != expectedSize) {
                throw IOException(
                    "Source ended at $copied bytes; provider reported $expectedSize bytes",
                )
            }
            if (temporary.length() != copied) {
                throw IOException(
                    "Temporary file size ${temporary.length()} does not match $copied copied bytes",
                )
            }

            Files.move(
                temporary.toPath(),
                absoluteTarget.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            published = true
            return copied
        } finally {
            if (!published && temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit()
            }
        }
    }
}
