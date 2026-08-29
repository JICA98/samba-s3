package com.zenithblue.sambas3.crash

import java.io.File
import java.io.RandomAccessFile

/** File-backed reader; UI never has to materialize a complete log. */
class CrashLogReader(private val file: File) {
    fun read(offset: Long, maxBytes: Int = 64 * 1024): String {
        if (!file.isFile || maxBytes <= 0) return ""
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset.coerceIn(0L, raf.length()))
            val bytes = ByteArray(maxBytes)
            val count = raf.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count)
        }
    }
    fun find(query: String, start: Long = 0L): Long {
        if (query.isBlank() || !file.isFile) return -1L
        var offset = start
        while (offset < file.length()) {
            val chunk = read(offset)
            val index = chunk.indexOf(query, ignoreCase = true)
            if (index >= 0) return offset + index
            offset += chunk.toByteArray().size.coerceAtLeast(1)
        }
        return -1L
    }
}
