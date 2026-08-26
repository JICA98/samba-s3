package com.zenithblue.sambas3.drivers.download

import java.io.File

enum class ArchiveFormat { ZIP, TZST, UNKNOWN }

object DriverArchiveFormat {
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK..
    private val ZSTD_MAGIC = byteArrayOf(0x28.toByte(), 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())

    fun detect(file: File): ArchiveFormat {
        if (!file.isFile || file.length() < 4) return ArchiveFormat.UNKNOWN
        val header = ByteArray(4)
        try {
            file.inputStream().use { it.read(header) }
        } catch (_: Exception) { return ArchiveFormat.UNKNOWN }
        return when {
            header.contentEquals(ZSTD_MAGIC) -> ArchiveFormat.TZST // tzst is zstd-compressed tar, starts with zstd magic
            header[0] == ZIP_MAGIC[0] && header[1] == ZIP_MAGIC[1] -> ArchiveFormat.ZIP
            else -> {
                // Fallback to extension
                val name = file.name.lowercase()
                when {
                    name.endsWith(".zip") -> ArchiveFormat.ZIP
                    name.endsWith(".tzst") || name.endsWith(".tar.zst") -> ArchiveFormat.TZST
                    else -> ArchiveFormat.UNKNOWN
                }
            }
        }
    }

    fun detectByName(name: String): ArchiveFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveFormat.ZIP
            lower.endsWith(".tzst") || lower.endsWith(".tar.zst") -> ArchiveFormat.TZST
            else -> ArchiveFormat.UNKNOWN
        }
    }
}
