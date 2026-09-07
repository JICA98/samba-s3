package com.zenithblue.sambas3.crash

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

class CrashLogReader(
    private val file: File,
    private val decodeGzip: Boolean = false,
) {
    var partial: Boolean = false
        private set

    val compressed: Boolean get() = decodeGzip

    fun sourceLength(): Long {
        if (!file.isFile) return 0L
        if (!decodeGzip) return file.length()
        return decodedBytes().size.toLong()
    }

    fun compressedLength(): Long = if (file.isFile) file.length() else 0L

    fun read(offset: Long, maxBytes: Int = CHUNK): String {
        val bytes = readBytes(offset, maxBytes)
        if (bytes.isEmpty()) return ""
        return String(bytes, Charsets.UTF_8)
    }

    fun find(query: String, start: Long = 0L): Long {
        if (query.isBlank() || !file.isFile) return -1L
        val needleBytes = query.toByteArray(Charsets.UTF_8)
        val overlap = needleBytes.size.coerceAtLeast(1)
        val length = sourceLength()
        var offset = start.coerceAtLeast(0L)
        while (offset < length) {
            val chunk = readBytes(offset, CHUNK)
            if (chunk.isEmpty()) break
            val aligned = dropLeadingContinuation(chunk, offset == 0L || !decodeGzip)
            val skip = chunk.size - aligned.size
            val decoded = String(aligned, Charsets.UTF_8)
            val index = decoded.indexOf(query, ignoreCase = true)
            if (index >= 0) {
                val prefix = decoded.substring(0, index).toByteArray(Charsets.UTF_8).size
                return offset + skip + prefix
            }
            val consumed = chunk.size
            if (offset + consumed >= length) break
            offset += (consumed - overlap).coerceAtLeast(1)
        }
        return -1L
    }

    fun readTail(maxBytes: Int = CHUNK): Pair<Long, String> {
        val length = sourceLength()
        val offset = (length - maxBytes.toLong()).coerceAtLeast(0L)
        return offset to read(offset, maxBytes)
    }

    private fun readBytes(offset: Long, maxBytes: Int): ByteArray {
        if (!file.isFile || maxBytes <= 0) return ByteArray(0)
        return if (decodeGzip) {
            val decoded = decodedBytes()
            val start = offset.coerceIn(0L, decoded.size.toLong()).toInt()
            val end = (start + maxBytes).coerceAtMost(decoded.size)
            if (start >= end) ByteArray(0) else decoded.copyOfRange(start, end)
        } else {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = offset.coerceIn(0L, length)
                raf.seek(start)
                val bytes = ByteArray(maxBytes)
                val count = raf.read(bytes)
                if (count <= 0) ByteArray(0) else bytes.copyOf(count)
            }
        }
    }

    private fun decodedBytes(): ByteArray {
        if (!file.isFile) return ByteArray(0)
        return try {
            file.inputStream().use { input ->
                GZIPInputStream(input).use { gz ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var copied = 0
                    while (copied < MAX_DECODE_BYTES) {
                        val n = gz.read(buf, 0, minOf(buf.size, MAX_DECODE_BYTES - copied))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                    }
                    if (copied >= MAX_DECODE_BYTES) partial = true
                    out.toByteArray()
                }
            }
        } catch (_: ZipException) {
            partial = true
            file.inputStream().use { input ->
                runCatching {
                    GZIPInputStream(input).use { gz ->
                        val out = ByteArrayOutputStream()
                        try {
                            gz.copyTo(out)
                        } catch (_: Exception) {
                            partial = true
                        }
                        out.toByteArray()
                    }
                }.getOrDefault(ByteArray(0))
            }
        } catch (_: Exception) {
            partial = true
            ByteArray(0)
        }
    }

    private fun dropLeadingContinuation(bytes: ByteArray, alreadyAligned: Boolean): ByteArray {
        if (alreadyAligned || bytes.isEmpty()) return bytes
        var i = 0
        while (i < bytes.size && i < 4 && (bytes[i].toInt() and 0xC0) == 0x80) i++
        return if (i == 0) bytes else bytes.copyOfRange(i, bytes.size)
    }

    companion object {
        const val CHUNK = 64 * 1024
        const val MAX_DECODE_BYTES = 16 * 1024 * 1024

        fun forArtifact(file: File): CrashLogReader =
            CrashLogReader(file, decodeGzip = file.name.endsWith(".gz", ignoreCase = true))
    }
}
